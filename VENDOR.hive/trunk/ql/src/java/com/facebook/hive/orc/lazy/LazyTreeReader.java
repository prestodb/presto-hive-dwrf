package com.facebook.hive.orc.lazy;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.facebook.hive.orc.BitFieldReader;
import com.facebook.hive.orc.InStream;
import com.facebook.hive.orc.OrcProto;
import com.facebook.hive.orc.PositionProvider;
import com.facebook.hive.orc.PositionProviderImpl;
import com.facebook.hive.orc.StreamName;
import com.facebook.hive.orc.OrcProto.RowIndex;

public abstract class LazyTreeReader {
  protected RowIndex index;
  protected long rowIndexStride;
  protected long previousRow;
  protected final int columnId;
  protected long rowBaseInStripe;
  private BitFieldReader present = null;
  // By default assume the value is not null
  protected boolean valuePresent = true;
  protected long previousPresentRow;
  private long numNonNulls;
  protected PositionProviderImpl previousPositionProvider = null;

  protected abstract void seek(PositionProvider index) throws IOException;

  /**
   * in the next rows values, returns the number of values which are not null
   *
   * @param rows
   * @return
   * @throws IOException
   */
  protected long countNonNulls(long rows) throws IOException {
    if (present != null) {
      long result = 0;
      for(long c=0; c < rows; ++c) {
        if (present.next() == 1) {
          result += 1;
        }
      }
      return result;
    } else {
      return rows;
    }
  }

  /**
   * Should only be called from within the tree reader once numNonNulls has been set correctly
   *
   * @throws IOException
   */
  protected abstract void skipRows(long numNonNullValues) throws IOException;

  /**
   * Should be called only from containers (lists, maps, structs, unions) since for these tree
   * readers the number of rows does not correspond to the number of values (e.g. there may
   * be many more if it contains the entries in lists with more than one element, or much less
   * if it is the fields in a struct where every other struct is null)
   *
   * @param numRows
   * @throws IOException
   */
  public void skipRowsInComplexType(long numRows) throws IOException {
    numNonNulls = countNonNulls(numRows);
    skipRows(numNonNulls);
  }

  /**
   * Should only be called from within the tree reader once it's been checked that the next
   * object isn't null
   *
   * @param previous
   * @return
   * @throws IOException
   */
  protected abstract Object next(Object previous) throws IOException;

  /**
   * Should be called only from containers (lists, maps, structs, unions) since for these tree
   * readers the number of rows does not correspond to the number of values (e.g. there may
   * be many more if it contains the entries in lists with more than one element, or much less
   * if it is the fields in a struct where every other struct is null)
   *
   * @param previous
   * @return
   * @throws IOException
   */
  public Object getInComplexType(Object previous) throws IOException {
    if (nextIsNullInComplexType()) {
      return null;
    }

    return next(previous);
  }

  /**
   * Returns whether or not the value corresponding to currentRow is null, suitable for calling
   * from outside
   *
   * @param currentRow
   * @return
   * @throws IOException
   */
  public boolean nextIsNull(long currentRow) throws IOException {
    if (present != null) {
      seekToPresentRow(currentRow);
      valuePresent = present.next() == 1;
      if (valuePresent) {
        // Adjust numNonNulls so we know how many non-null values we'll need to skip if skip is
        // called
        numNonNulls++;
      } else {
        if (currentRow == previousRow + 1) {
          // if currentRow = previousRow + 1, and the value is null this is equivalent to going to the
          // next row.  if there's a series of nulls this prevents us from calling skipRows when
          // the next non-null value is fetched
          previousRow++;
        }
      }
    }

    return !valuePresent;
  }

  /**
   * Should be called only from containers (lists, maps, structs, unions) since for these tree
   * readers the number of rows does not correspond to the number of values (e.g. there may
   * be many more if it contains the entries in lists with more than one element, or much less
   * if it is the fields in a struct where every other struct is null)
   *
   * @return
   * @throws IOException
   */
  public boolean nextIsNullInComplexType() throws IOException {
    if (present != null) {
      valuePresent = present.next() == 1;
      if (valuePresent) {
        numNonNulls++;
      }
    }

    return !valuePresent;
  }

  /**
   * Computes the number of the row index entry that immediately precedes row
   *
   * @param row
   * @return
   */
  private int computeRowIndexEntry(long row) {
    return rowIndexStride > 0 ? (int) ((row - rowBaseInStripe - 1) / rowIndexStride) : 0;
  }

  /**
   * Shifts only the present stream so that next will return the value for currentRow
   *
   * @param currentRow
   * @throws IOException
   */
  protected void seekToPresentRow(long currentRow) throws IOException {
    if (currentRow != previousPresentRow + 1) {
      long rowInStripe = currentRow - rowBaseInStripe - 1;
      int rowIndexEntry = computeRowIndexEntry(currentRow);
      if (rowIndexEntry != computeRowIndexEntry(previousRow) || currentRow < previousPresentRow) {
        previousPositionProvider = new PositionProviderImpl(index.getEntry(rowIndexEntry));
        present.seek(previousPositionProvider);
        numNonNulls = countNonNulls(rowInStripe - (rowIndexEntry * rowIndexStride));
      } else {
        numNonNulls += countNonNulls(currentRow - previousPresentRow - 1);
      }
    }
    previousPresentRow = currentRow;
  }

  public LazyTreeReader(int columnId, long rowIndexStride) {
    this.columnId = columnId;
    this.rowIndexStride = rowIndexStride;
    this.previousRow = 0;
    this.previousPresentRow = 0;
    this.numNonNulls = 0;
  }

  /**
   * Returns the value corresponding to currentRow, suitable for calling from outside
   *
   * @param currentRow
   * @param previous
   * @return
   * @throws IOException
   */
  public Object get(long currentRow, Object previous) throws IOException {
    seekToRow(currentRow);
    return next(previous);
  }

  /**
   * Adjust all streams to the beginning of the row index entry specified, backwards means that
   * a previous value is being read and forces the index entry to be restarted, otherwise, has
   * no effect if we're already in the current index entry
   *
   * @param rowIndexEntry
   * @param backwards
   * @throws IOException
   */
  protected void seek(int rowIndexEntry, boolean backwards) throws IOException {
    if (backwards || rowIndexEntry != computeRowIndexEntry(previousRow)) {
      if (backwards || rowIndexEntry != computeRowIndexEntry(previousPresentRow)) {
        previousPositionProvider = new PositionProviderImpl(index.getEntry(rowIndexEntry));
        if (present != null) {
          present.seek(previousPositionProvider);
        }
      }
      seek(previousPositionProvider);
    }
  }

  /**
   * Adjusts all streams so that the next value returned corresponds to the value for currentRow,
   * suitable for calling from outside
   *
   * @param currentRow
   * @return
   * @throws IOException
   */
  protected boolean seekToRow(long currentRow) throws IOException {
    boolean seeked = false;

    // If this holds it means we checked if the row is null, and it was not,
    // and when we skipRows we don't want to skip this row.
    if (currentRow != previousPresentRow) {
      nextIsNull(currentRow);
    }
    if (currentRow != previousRow + 1 && valuePresent) {
      numNonNulls--;
      long rowInStripe = currentRow - rowBaseInStripe - 1;
      int rowIndexEntry = computeRowIndexEntry(currentRow);
      if (rowIndexEntry != computeRowIndexEntry(previousRow) || currentRow < previousRow) {
        if (present == null) {
          previousPositionProvider = new PositionProviderImpl(index.getEntry(rowIndexEntry));
          numNonNulls = countNonNulls(rowInStripe - (rowIndexEntry * rowIndexStride));
        }
        seek(rowIndexEntry, currentRow <= previousRow);
        skipRows(numNonNulls);
      } else {
         if (present == null) {
           // If present is null, it means there are no nulls in this column
           // so the number of nonNulls is the number of rows being skipped
           numNonNulls = currentRow - previousRow - 1;
         }
        skipRows(numNonNulls);
      }
      seeked = true;
    }

    numNonNulls = 0;
    previousRow = currentRow;
    return seeked;
  }

  public void startStripe(Map<StreamName, InStream> streams, List<OrcProto.ColumnEncoding> encodings,
      RowIndex[] indexes, long rowBaseInStripe) throws IOException {
    this.index = indexes[columnId];
    this.previousRow = rowBaseInStripe;
    this.previousPresentRow = rowBaseInStripe;
    this.rowBaseInStripe = rowBaseInStripe;

    InStream in = streams.get(new StreamName(columnId,
        OrcProto.Stream.Kind.PRESENT));
    if (in == null) {
      present = null;
      valuePresent = true;
    } else {
      present = new BitFieldReader(in);
    }
  }
}
