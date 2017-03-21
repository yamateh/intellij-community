/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.execution;

import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.intellij.openapi.util.text.StringUtil.*;

public class CommandLineUtil {
  private static final char SPECIAL_QUOTE = '\uEFEF';

  private static final Pattern WIN_BACKSLASHES_PRECEDING_QUOTE = Pattern.compile("(\\\\+)(?=\"|$)");
  private static final Pattern WIN_CARET_SPECIAL = Pattern.compile("[&<>()@^|]");
  private static final Pattern WIN_QUOTE_SPECIAL = Pattern.compile("[ \t\"*]");
  private static final Pattern WIN_QUIET_COMMAND = Pattern.compile("((?:@\\s*)++)(.*)", Pattern.CASE_INSENSITIVE);

  private static final char Q = '\"';
  private static final String QQ = "\"\"";

  @NotNull
  public static String specialQuote(@NotNull String parameter) {
    return quote(parameter, SPECIAL_QUOTE);
  }

  @NotNull
  public static List<String> toCommandLine(@NotNull List<String> command) {
    assert !command.isEmpty();
    return toCommandLine(command.get(0), command.subList(1, command.size()));
  }

  @NotNull
  public static List<String> toCommandLine(@NotNull String command, @NotNull List<String> parameters) {
    return toCommandLine(command, parameters, Platform.current());
  }

  // please keep an implementation in sync with [junit-rt] ProcessBuilder.createProcess()
  @NotNull
  public static List<String> toCommandLine(@NotNull String command, @NotNull List<String> parameters, @NotNull Platform platform) {
    List<String> commandLine = ContainerUtil.newArrayListWithCapacity(parameters.size() + 1);

    commandLine.add(FileUtilRt.toSystemDependentName(command, platform.fileSeparator));

    if (platform != Platform.WINDOWS) {
      for (String parameter : parameters) {
        if (isQuoted(parameter, SPECIAL_QUOTE)) {
          // TODO do we need that on non-Windows? M.b. just remove these quotes? -- Eldar
          parameter = quote(unquoteString(parameter, SPECIAL_QUOTE), Q);
        }
        commandLine.add(parameter);
      }
    }
    else {
      addToWindowsCommandLine(parameters, commandLine);
    }

    return commandLine;
  }

  /*
   * Windows command line escaping rules are tricky and poorly documented, so the code below might require a bit of explanation.
   *
   * Here're the rules that define our implementation, and some peculiarities to know:
   *
   *   *** On Windows, there's no ARGV concept at the OS level; all parameters are passed as a single command line
   *       string, which is only parsed into the ARGV by CRT if needed (this is used by the most applications).
   *
   *       CRT parsing rules are relatively simple:
   *
   *        - Parameters are delimited using spaces:        [foo] [bar] [baz]  ->  [foo bar baz]
   *        - Whitespaces are escaped using double quotes:  [foo bar] [baz]    ->  ["foo bar" baz]
   *        - Double-quotes are escaped using backslashes:  [foo bar] ["baz"]  ->  ["foo bar" \"baz\"]
   *
   *        - Backslashes are treated literally unless they precede a double quote, otherwise they need to be
   *          backslash-escaped as well:
   *
   *              [C:\Program Files\] ["backslash quote\"]
   *
   *                   -> ["C:\Program Files\\" "\"backslash quote\\\""]
   *
   *
   *   *** In case a command line is wrapped using CMD.EXE call (that is, `cmd /d /c call executable args...`, which
   *       is quite common), additional escaping rules apply.
   *
   *       CMD.EXE treat few chars in a special way (piping, command chaining, etc.), these are: [&<>()@|^].
   *       The CMD.EXE command line parser has two means of escaping these special chars: quote flag and caret-escaping.
   *       The main rules in a nutshell are:
   *
   *         - A quote ["] toggles the quote flag; while the quote flag is active, the special chars are no longer
   *           special. Note that quotes themselves are NOT removed at this stage:  ["<^.^>"] => ["<^.^>"]
   *           The quotes might be removed by the CRT command line parser later on when reconstructing the ARGV,
   *           if the executable opts to.
   *
   *         - A char following a caret [^] has no special meaning, the caret itself is removed: [^<^^.^^^>] => [<^.^>]
   *
   *       These rules, in turn, have special cases that affect the implementation, see below.
   *
   *
   *   *** [CMD] As already mentioned, the CMD special chars [&<>()@|^] are sensitive to the quote flag, which is toggled
   *       whenever the command line parser encounters a quote, no matter whether backslash-escaped or regular one:
   *
   *           [foo "bar" baz]
   *
   *                -> ["foo \"bar\" baz"]               # enclosed in quotes due to whitespaces inside for CRT
   *                                                                    # quote flag:
   *                   [ ^^^^^      ^^^^ ]                              #   ON:   [&<>()@|^] lose special meaning
   *                   [       ^^^^      ]                              #   OFF:  [&<>()@|^] must be ^-escaped
   *
   *       This gets even more confusing when dealing with caret-escaping state across multiple arguments in case some
   *       of them have odd number of quotes:
   *
   *           [C:\Program Files\...]  ["]  [f o]  [b"r]
   *
   *                -> ["C:\Program Files\..."   \"   "f o"   b\"r]
   *                                                                    # quote flag:
   *                   [ ^^^^^^^^^^^^^^^^^^^^      ^^^     ^^^^^  ]     #   ON:   [&<>()@|^] lose special meaning
   *                   [                      ^^^^     ^^^       ^]     #   OFF:  [&<>()@|^] must be ^-escaped
   *
   *       However, this is a totally valid case considering that arguments are passed as a single command line string
   *       under the hood. Anyway, the point is, we need to count all the quotes in order to properly escape the special
   *       chars. In the following sections we describe our escaping approach w.r.t. the quote flag.
   *
   *
   *   *** [CMD] A caret [^] is always ^-escaped with the quote flag OFF:
   *
   *           [^]                                       # original value
   *                -> [^^]                              # escaping
   *                        -> [^]                       # execution
   *
   *       Why not just use a caret within quotes ["^"] (i.e. with the quote flag ON) here?
   *
   *       Because of the way CMD.EXE handles the CALL command. Due to the nature of the CALL command, CMD.EXE has
   *       to process the ^-escaped special chars twice. In order to preserve the number of carets, which would be
   *       halved after the second expansion otherwise, it duplicates all the carets behind the scenes beforehand:
   *
   *           [^]                                       # original value
   *                -> [^^]                              # escaping
   *                        -> [^^^^]                    # under the hood: CALL caret doubling
   *                                -> [^^]              # under the hood: CALL second expansion
   *                                        -> [^]       # execution
   *
   *       Unfortunately it blindly doubles all carets, with no regard to the quote flag. But a quoted caret is not
   *       treated as an escape, hence it's not consumed. These carets appear duplicated to a process being called:
   *
   *           [^]                                       # original value
   *                -> ["^"]                             # escaping
   *                        -> ["^^"]                    # under the hood: CALL caret doubling
   *                                -> ["^^"]            # under the hood: CALL second expansion
   *                                        -> [^^]      # execution (oops...)
   *
   *
   *   *** [CMD] The rest special chars ([&<>()@|] except the caret) are quoted instead (i.e. emitted with the quote
   *       flag ON) instead of being ^-escaped (with the only exception of ECHO command, see below):
   *
   *           [&]                                       # original value
   *               -> ["&"]                              # escaping
   *                        -> [&]                       # execution
   *
   *       Why not use ^-escaping, just like for the caret itself? Again, because of the CALL caret doubling:
   *
   *           [&]                                       # original value
   *                -> [^&]                              # escaping
   *                        -> [^^&]                     # under the hood: CALL caret doubling
   *                                -> [^]               # under the hood: CALL second expansion (stray [&] may lead to errors)
   *                                        -> []        # execution (oops...)
   *
   *   *** [CMD] [ECHO] The ECHO command doesn't use CRT for parsing the ARGV, so only the common rules for CMD parameters
   *       apply. That is, we ^-escape all the special chars [&<>()@|^].
   *
   *
   * Useful links:
   *
   *   * https://ss64.com/nt/syntax-esc.html  Syntax : Escape Characters, Delimiters and Quotes
   *   * http://stackoverflow.com/a/4095133/545027  How does the Windows Command Interpreter (CMD.EXE) parse scripts?
   */

  private static void addToWindowsCommandLine(@NotNull List<String> parameters,
                                              @NotNull List<String> commandLine) {
    String command = commandLine.get(0);

    boolean isCmdParam = isWinShell(command);
    int cmdInvocationDepth = isWinShellScript(command) ? 2 : isCmdParam ? 1 : 0;

    QuoteFlag quoteFlag = new QuoteFlag(command);

    for (int i = 0; i < parameters.size(); i++) {
      String parameter = parameters.get(i);

      parameter = unquoteString(parameter, SPECIAL_QUOTE);
      boolean inescapableQuoting = !parameter.equals(parameters.get(i));

      if (parameter.isEmpty()) {
        commandLine.add(QQ);
        continue;
      }

      if (isCmdParam && parameter.startsWith("/") && parameter.length() == 2) {
        commandLine.add(parameter);
        continue;
      }

      String parameterPrefix = "";
      if (isCmdParam) {
        Matcher m = WIN_QUIET_COMMAND.matcher(parameter);
        if (m.matches()) {
          parameterPrefix = m.group(1);  // @...
          parameter = m.group(2);
        }

        if (parameter.equalsIgnoreCase("echo")) {
          // no further quoting, only ^-escape and wrap the whole "echo ..." into double quotes
          String parametersJoin = join(ContainerUtil.subList(parameters, i), " ");
          parameter = escapeParameter(parametersJoin, quoteFlag.toggle(), cmdInvocationDepth, false);
          commandLine.add(parameter);  // prefix is already included
          break;
        }

        if (!parameter.equalsIgnoreCase("call")) {
          isCmdParam = isWinShell(parameter);
          if (isCmdParam || isWinShellScript(parameter)) {
            cmdInvocationDepth++;
          }
        }
      }

      if (cmdInvocationDepth > 0 && !isCmdParam || inescapableQuoting) {
        parameter = escapeParameter(parameter, quoteFlag, cmdInvocationDepth, !inescapableQuoting);
      }
      else {
        parameter = backslashEscapeQuotes(parameter);
      }

      commandLine.add(parameterPrefix + parameter);
    }
  }

  @NotNull
  @Contract(pure = true)
  public static String escapeParameterOnWindows(@NotNull String s, boolean isWinShell) {
    boolean hadLineBreaks = !s.equals(s = convertLineSeparators(s, ""));
    if (s.isEmpty()) return QQ;
    String ret = isWinShell ? escapeParameter(s, new QuoteFlag(hadLineBreaks), 1, true) : backslashEscapeQuotes(s);
    return hadLineBreaks ? quote(ret, Q) : ret;
  }

  @NotNull
  @Contract(pure = true)
  private static String escapeParameter(@NotNull String s,
                                        @NotNull QuoteFlag quoteFlag,
                                        int cmdInvocationDepth,
                                        boolean escapeQuotingInside) {
    final StringBuilder sb = new StringBuilder();
    final String escapingCarets = repeatSymbol('^', (1 << cmdInvocationDepth) - 1);

    return (escapeQuotingInside ? quoteEscape(sb, s, quoteFlag, escapingCarets)
                                : caretEscape(sb, s, quoteFlag, escapingCarets)).toString();
  }

  /**
   * Escape a parameter for passing it to CMD.EXE, that is only ^-escape and wrap it with double quotes,
   * but do not touch any double quotes or backslashes inside.
   *
   * @param escapingCarets 2^n-1 carets for escaping the special chars
   * @return sb
   */
  @NotNull
  private static StringBuilder caretEscape(@NotNull StringBuilder sb,
                                           @NotNull String s,
                                           @NotNull QuoteFlag quoteFlag,
                                           @NotNull String escapingCarets) {
    quoteFlag.toggle();
    sb.append(Q);

    int lastPos = 0;
    final Matcher m = WIN_CARET_SPECIAL.matcher(s);
    while (m.find()) {
      quoteFlag.consume(s, lastPos, m.start());
      sb.append(s, lastPos, m.start());

      if (!quoteFlag.isEnabled()) sb.append(escapingCarets);
      sb.append(m.group());

      lastPos = m.end();
    }
    quoteFlag.consume(s, lastPos, s.length());
    sb.append(s, lastPos, s.length());

    quoteFlag.toggle();
    return sb.append(Q);
  }

  /**
   * Escape a parameter for passing it to Windows application through CMD.EXE, that is ^-escape + quote.
   *
   * @param escapingCarets 2^n-1 carets for escaping the special chars
   * @return sb
   */
  @NotNull
  private static StringBuilder quoteEscape(@NotNull StringBuilder sb,
                                           @NotNull String s,
                                           @NotNull QuoteFlag quoteFlag,
                                           @NotNull String escapingCarets) {
    int lastPos = 0;
    final Matcher m = WIN_CARET_SPECIAL.matcher(s);
    while (m.find()) {
      quoteFlag.consume(s, lastPos, m.start());
      appendQuoted(sb, s.substring(lastPos, m.start()));

      String specialText = m.group();
      boolean isCaret = specialText.equals("^");
      if (isCaret) specialText = escapingCarets + specialText;  // only a caret is escaped using carets
      if (isCaret == quoteFlag.isEnabled()) {
        // a caret must be always outside quotes: close a quote temporarily, put a caret, reopen a quote
        // rest special chars are always inside: quote and append them as usual
        appendQuoted(sb, specialText);  // works for both cases
      }
      else {
        sb.append(specialText);
      }

      lastPos = m.end();
    }
    quoteFlag.consume(s, lastPos, s.length());
    appendQuoted(sb, s.substring(lastPos));

    // JDK ProcessBuilder implementation on Windows checks each argument to contain whitespaces and encloses it in
    // double quotes unless it's already quoted. Since our escaping logic is more complicated, we may end up with
    // something like [^^"foo bar"], which is not a quoted string, strictly speaking; it would require additional
    // quotes from the JDK's point of view, which in turn ruins the quoting and caret state inside the string.
    //
    // Here, we prepend and/or append a [""] token (2 quotes to preserve the parity), if necessary, to make the result
    // look like a properly quoted string:
    //
    //     [^^"foo bar"] -> [""^^"foo bar"]  # starts and ends with quotes
    //
    if (!isQuoted(sb, Q) && indexOfAny(sb, " \t") >= 0) {
      if (sb.charAt(0) != Q) sb.insert(0, QQ);
      if (sb.charAt(sb.length() - 1) != Q) sb.append(QQ);
    }

    return sb;
  }

  /**
   * Appends the string to the buffer, quoting the former if necessary.
   *
   * @return sb
   */
  @SuppressWarnings("UnusedReturnValue")
  private static StringBuilder appendQuoted(@NotNull StringBuilder sb, @NotNull String s) {
    if (s.isEmpty()) return sb;

    s = backslashEscapeQuotes(s);
    if (WIN_CARET_SPECIAL.matcher(s).find()) s = quote(s, Q);

    // Can't just concatenate two quoted strings, like ["foo"] and ["bar"],
    // the two quotes inside would be treated as ["] upon unescaping, that is:
    //
    //   ["foo""bar"] -> [foo"bar]  # [""""] -> ["] (oops...)
    //
    int numTrailingBackslashes = removeClosingQuote(sb);
    if (numTrailingBackslashes < 0) {
      // sb was not quoted at its end
      return sb.append(s);
    }

    s = unquoteString(s, Q);

    if (WIN_BACKSLASHES_PRECEDING_QUOTE.matcher(s).matches()) {
      // those trailing backslashes left in the buffer (if any) are going to precede a quote, double them
      repeatSymbol(sb, '\\', numTrailingBackslashes);
    }
    return sb.append(s)
      .append(Q);
  }

  @NotNull
  @Contract(pure = true)
  private static String backslashEscapeQuotes(@NotNull String s) {
    assert !s.isEmpty();

    String result = WIN_BACKSLASHES_PRECEDING_QUOTE.matcher(s)
      .replaceAll("$1$1")      // duplicate trailing backslashes and those preceding a double quote
      .replace("\"", "\\\"");  // backslash-escape all double quotes

    if (!result.equals(s) || WIN_QUOTE_SPECIAL.matcher(s).find()) {
      result = quote(result, Q);
    }
    return result;
  }

  /**
   * If the buffer ends with a double-quoted token, "reopen" it by deleting the closing quote.
   *
   * @param sb the buffer being modified
   * @return -1 if the buffer doesn't end with a quotation (in this case it's left unmodified),
   * otherwise a number of trailing backslashes left in the buffer, if any
   */
  private static int removeClosingQuote(@NotNull StringBuilder sb) {
    if (sb.length() < 2 || sb.charAt(sb.length() - 1) != Q) return -1;

    // Strip the closing quote and halve the number of trailing backslashes (if any):
    // they no more precede a double quote, hence lose their special treatment during unescaping.
    sb.setLength(sb.length() - 1);
    int numTrailingBackslashes = sb.length() - trimTrailing(sb, '\\').length();
    repeatSymbol(sb, '\\', numTrailingBackslashes / 2);

    if (numTrailingBackslashes % 2 == 1) {
      // retreat, it was a backslash-escaped quote, restore it
      repeatSymbol(sb, '\\', numTrailingBackslashes / 2 + 1);
      sb.append(Q);
      return -1;
    }

    return numTrailingBackslashes / 2;
  }


  private static boolean isWinShell(@NotNull String command) {
    return "cmd".equalsIgnoreCase(command) || "cmd.exe".equalsIgnoreCase(command);
  }

  private static boolean isWinShellScript(@NotNull String command) {
    return endsWithIgnoreCase(command, ".cmd") || endsWithIgnoreCase(command, ".bat");
  }

  private static boolean endsWithIgnoreCase(@NotNull String str, @NotNull String suffix) {
    return str.regionMatches(true, str.length() - suffix.length(), suffix, 0, suffix.length());
  }

  @NotNull
  private static String quote(@NotNull String s, char ch) {
    return !isQuoted(s, ch) ? ch + s + ch : s;
  }

  private static boolean isQuoted(@NotNull CharSequence s, char ch) {
    return s.length() >= 2 && s.charAt(0) == ch && s.charAt(s.length() - 1) == ch;
  }

  public static boolean VERBOSE_COMMAND_LINE_MODE;
  @NotNull
  public static String extractPresentableName(@NotNull String commandLine) {
    String executable = commandLine.trim();

    List<String> words = splitHonorQuotes(executable, ' ');
    String execName;
    List<String> args;
    if (words.isEmpty()) {
      execName = executable;
      args = Collections.emptyList();
    }
    else {
      execName = words.get(0);
      args = words.subList(1, words.size());
    }

    if (VERBOSE_COMMAND_LINE_MODE) {
      return shortenPathWithEllipsis(execName + " " + join(args, " "), 250);
    }

    return new File(unquoteString(execName)).getName();
  }

  /**
   * Counts quote parity needed to ^-escape special chars on Windows properly.
   */
  private static class QuoteFlag {
    private boolean myValue;

    public QuoteFlag(boolean value) {
      myValue = value;
    }

    private QuoteFlag(@NotNull CharSequence s) {
      consume(s);
    }

    public boolean isEnabled() {
      return myValue;
    }

    @NotNull
    public QuoteFlag toggle() {
      myValue = !myValue;
      return this;
    }

    @NotNull
    public QuoteFlag consume(@NotNull CharSequence s) {
      return consume(s, 0, s.length());
    }

    @NotNull
    public QuoteFlag consume(@NotNull CharSequence s, int start, int end) {
      myValue ^= countChars(s, Q, start, end, false) % 2 != 0; // count all, no matter whether backslash-escaped or not
      return this;
    }
  }
}
