def array(p_object, dtype=None, copy=True, order=None, subok=False, ndmin=0): # real signature unknown; restored from __doc__
    """
    array(object, dtype=None, copy=True, order=None, subok=False, ndmin=0)

        Create an array.

        Parameters
        ----------
        object : array_like
            An array, any object exposing the array interface, an
            object whose __array__ method returns an array, or any
            (nested) sequence.
        dtype : data-type, optional
            The desired data-type for the array.  If not given, then
            the type will be determined as the minimum type required
            to hold the objects in the sequence.  This argument can only
            be used to 'upcast' the array.  For downcasting, use the
            .astype(t) method.
        copy : bool, optional
            If true (default), then the object is copied.  Otherwise, a copy
            will only be made if __array__ returns a copy, if obj is a
            nested sequence, or if a copy is needed to satisfy any of the other
            requirements (`dtype`, `order`, etc.).
        order : {'C', 'F', 'A'}, optional
            Specify the order of the array.  If order is 'C' (default), then the
            array will be in C-contiguous order (last-index varies the
            fastest).  If order is 'F', then the returned array
            will be in Fortran-contiguous order (first-index varies the
            fastest).  If order is 'A', then the returned array may
            be in any order (either C-, Fortran-contiguous, or even
            discontiguous).
        subok : bool, optional
            If True, then sub-classes will be passed-through, otherwise
            the returned array will be forced to be a base-class array (default).
        ndmin : int, optional
            Specifies the minimum number of dimensions that the resulting
            array should have.  Ones will be pre-pended to the shape as
            needed to meet this requirement.

        Returns
        -------
        out : ndarray
            An array object satisfying the specified requirements.

        See Also
        --------
        empty, empty_like, zeros, zeros_like, ones, ones_like, fill

        Examples
        --------
        >>> np.array([1, 2, 3])
        array([1, 2, 3])

        Upcasting:

        >>> np.array([1, 2, 3.0])
        array([ 1.,  2.,  3.])

        More than one dimension:

        >>> np.array([[1, 2], [3, 4]])
        array([[1, 2],
               [3, 4]])

        Minimum dimensions 2:

        >>> np.array([1, 2, 3], ndmin=2)
        array([[1, 2, 3]])

        Type provided:

        >>> np.array([1, 2, 3], dtype=complex)
        array([ 1.+0.j,  2.+0.j,  3.+0.j])

        Data-type consisting of more than one element:

        >>> x = np.array([(1,2),(3,4)],dtype=[('a','<i4'),('b','<i4')])
        >>> x['a']
        array([1, 3])

        Creating an array from sub-classes:

        >>> np.array(np.mat('1 2; 3 4'))
        array([[1, 2],
               [3, 4]])

        >>> np.array(np.mat('1 2; 3 4'), subok=True)
        matrix([[1, 2],
                [3, 4]])
    """
    pass

class ndarray(object):
    """
    ndarray(shape, dtype=float, buffer=None, offset=0,
                strides=None, order=None)

        An array object represents a multidimensional, homogeneous array
        of fixed-size items.  An associated data-type object describes the
        format of each element in the array (its byte-order, how many bytes it
        occupies in memory, whether it is an integer, a floating point number,
        or something else, etc.)

        Arrays should be constructed using `array`, `zeros` or `empty` (refer
        to the See Also section below).  The parameters given here refer to
        a low-level method (`ndarray(...)`) for instantiating an array.

        For more information, refer to the `numpy` module and examine the
        the methods and attributes of an array.

        Parameters
        ----------
        (for the __new__ method; see Notes below)

        shape : tuple of ints
            Shape of created array.
        dtype : data-type, optional
            Any object that can be interpreted as a numpy data type.
        buffer : object exposing buffer interface, optional
            Used to fill the array with data.
        offset : int, optional
            Offset of array data in buffer.
        strides : tuple of ints, optional
            Strides of data in memory.
        order : {'C', 'F'}, optional
            Row-major or column-major order.

        Attributes
        ----------
        T : ndarray
            Transpose of the array.
        data : buffer
            The array's elements, in memory.
        dtype : dtype object
            Describes the format of the elements in the array.
        flags : dict
            Dictionary containing information related to memory use, e.g.,
            'C_CONTIGUOUS', 'OWNDATA', 'WRITEABLE', etc.
        flat : numpy.flatiter object
            Flattened version of the array as an iterator.  The iterator
            allows assignments, e.g., ``x.flat = 3`` (See `ndarray.flat` for
            assignment examples; TODO).
        imag : ndarray
            Imaginary part of the array.
        real : ndarray
            Real part of the array.
        size : int
            Number of elements in the array.
        itemsize : int
            The memory use of each array element in bytes.
        nbytes : int
            The total number of bytes required to store the array data,
            i.e., ``itemsize * size``.
        ndim : int
            The array's number of dimensions.
        shape : tuple of ints
            Shape of the array.
        strides : tuple of ints
            The step-size required to move from one element to the next in
            memory. For example, a contiguous ``(3, 4)`` array of type
            ``int16`` in C-order has strides ``(8, 2)``.  This implies that
            to move from element to element in memory requires jumps of 2 bytes.
            To move from row-to-row, one needs to jump 8 bytes at a time
            (``2 * 4``).
        ctypes : ctypes object
            Class containing properties of the array needed for interaction
            with ctypes.
        base : ndarray
            If the array is a view into another array, that array is its `base`
            (unless that array is also a view).  The `base` array is where the
            array data is actually stored.

        See Also
        --------
        array : Construct an array.
        zeros : Create an array, each element of which is zero.
        empty : Create an array, but leave its allocated memory unchanged (i.e.,
                it contains "garbage").
        dtype : Create a data-type.

        Notes
        -----
        There are two modes of creating an array using ``__new__``:

        1. If `buffer` is None, then only `shape`, `dtype`, and `order`
           are used.
        2. If `buffer` is an object exposing the buffer interface, then
           all keywords are interpreted.

        No ``__init__`` method is needed because the array is fully initialized
        after the ``__new__`` method.

        Examples
        --------
        These examples illustrate the low-level `ndarray` constructor.  Refer
        to the `See Also` section above for easier ways of constructing an
        ndarray.

        First mode, `buffer` is None:

        >>> np.ndarray(shape=(2,2), dtype=float, order='F')
        array([[ -1.13698227e+002,   4.25087011e-303],
               [  2.88528414e-306,   3.27025015e-309]])         #random

        Second mode:

        >>> np.ndarray((2,), buffer=np.array([1,2,3]),
        ...            offset=np.int_().itemsize,
        ...            dtype=int) # offset = 1*itemsize, i.e. skip first element
        array([2, 3])
    """
    pass