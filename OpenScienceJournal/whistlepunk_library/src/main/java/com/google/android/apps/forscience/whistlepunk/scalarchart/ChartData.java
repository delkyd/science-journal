/*
 *  Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk.scalarchart;

import android.support.annotation.VisibleForTesting;

import com.google.android.apps.forscience.whistlepunk.metadata.Label;
import com.google.android.apps.forscience.whistlepunk.sensorapi.StreamStat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class ChartData {
    public static class DataPoint {

        private final long mX;
        private final double mY;

        public DataPoint(long x, double y) {
            mX = x;
            mY = y;
        }

        public long getX() {
            return mX;
        }

        public double getY() {
            return mY;
        }

        /**
         * For debugging only
         */
        @Override
        public String toString() {
            return String.format("(%d,%.3g)", mX, mY);
        }
    }

    // The number of indicies that an approximate binary search may be off.
    // Larger numbers cause binary search to be faster at the risk of drawing unnecessary points.
    // TODO: Look into tweaking this number for utmost efficency and memory usage!
    @VisibleForTesting
    private static final int DEFAULT_APPROX_RANGE = 8;

    public static final int DEFAULT_THROWAWAY_THRESHOLD = 100;
    private int mThrowawayDataSizeThreshold;

    private List<DataPoint> mData = new ArrayList<>();

    // The list of data points at which a label should be displayed.
    private List<DataPoint> mLabels = new ArrayList<>();

    // The list of Label objects which are not yet converted into DataPoints and added to the
    // mLabels list. This happens when the Label is outside of the range for which we have data,
    // so we cannot calculate where that label should be drawn.
    private List<Label> mUnaddedLabels = new ArrayList<>();

    // The stats for this list.
    private List<StreamStat> mStats = new ArrayList<>();

    private static final Comparator<? super DataPoint> DATA_POINT_COMPARATOR =
            new Comparator<DataPoint>() {
        @Override
        public int compare(DataPoint lhs, DataPoint rhs) {
            return Long.compare(lhs.getX(), rhs.getX());
        }
    };

    public ChartData() {
        this(DEFAULT_THROWAWAY_THRESHOLD);
    }

    public ChartData(int throwawayDataSizeThreshold) {
        mThrowawayDataSizeThreshold = throwawayDataSizeThreshold;
    }

    // This assumes the data point occurs after all previous data points.
    // Order is not checked.
    public void addPoint(DataPoint point) {
        mData.add(point);
        if (mUnaddedLabels.size() > 0) {
            // TODO to avoid extra work, only try again if new data might come in in the direction
            // of these labels...?
            Iterator<Label> unaddedLabelIterator = mUnaddedLabels.iterator();
            while (unaddedLabelIterator.hasNext()) {
                Label next = unaddedLabelIterator.next();
                if (tryAddingLabel(next)) {
                    unaddedLabelIterator.remove();
                }
            }
        }
    }

    public List<DataPoint> getPoints() {
        return mData;
    }

    // This assumes the List<DataPoint> is ordered by timestamp.
    public void setPoints(List<DataPoint> data) {
        mData = data;
    }

    public void addOrderedGroupOfPoints(List<DataPoint> points) {
        if (points == null || points.size() == 0) {
            return;
        }
        mData.addAll(points);
        Collections.sort(mData, DATA_POINT_COMPARATOR);
    }

    public List<DataPoint> getPointsInRangeToEnd(long xMin) {
        int startIndex = approximateBinarySearch(xMin, 0, true);
        return mData.subList(startIndex, mData.size());
    }

    public List<DataPoint> getPointsInRange(long xMin, long xMax) {
        int startIndex = approximateBinarySearch(xMin, 0, true);
        int endIndex = approximateBinarySearch(xMax, startIndex, false);
        if (startIndex > endIndex) {
            return Collections.emptyList();
        }
        return mData.subList(startIndex, endIndex + 1);
    }

    public DataPoint getClosestDataPointToTimestamp(long timestamp) {
        int index = getClosestIndexToTimestamp(timestamp);
        if (mData.size() == 0) {
            return null;
        }
        return mData.get(index);
    }

    // Searches for the closest index to a given timestamp, round up or down if the search
    // does not find an exact match, to the closest timestamp.
    public int getClosestIndexToTimestamp(long timestamp) {
        return exactBinarySearch(timestamp, 0);
    }

    /**
     * Searches for the index of the value that is equal to or just less than the search X value, in
     * the range of startSearchIndex to the end of the data array.
     * @param searchX The X value to search for
     * @param startSearchIndex The index into the data where the search starts
     * @return The exact index of the value at or just below the search X value.
     */
    @VisibleForTesting
    int exactBinarySearch(long searchX, int startSearchIndex) {
        return approximateBinarySearch(searchX, startSearchIndex, mData.size() - 1, true, 0);
    }

    /**
     * A helper function to search for the index of the point with the closest value to the searchX
     * provided, using the default approximate search range.
     * @param searchX The value to search for
     * @param startSearchIndex The index into the data where the search starts
     * @param preferStart Whether the approximate result should prefer the start of a range or
     *                    the end of a range. This can be used to make sure the range is not
     *                    too short.
     * @return The index of an approximate X match in the array
     */
    private int approximateBinarySearch(long searchX, int startSearchIndex, boolean preferStart) {
        return approximateBinarySearch(searchX, startSearchIndex, mData.size() - 1, preferStart,
                DEFAULT_APPROX_RANGE);
    }

    /**
     * Searches for the index of the point with the closest value to the searchX provided.
     * Does not try for an exact match, rather returns when the range is smaller than the
     * approximateSearchRange. Assumes points are ordered.
     * @param searchX The value to search for
     * @param startIndex The index into the data where the search starts
     * @param endIndex The index where the search ends
     * @param preferStart Whether the approximate result should prefer the start of a range or
     *                    the end of a range. This can be used to make sure the range is not
     *                    too short.
     * @param searchRange The size of the range at which we can stop searching and just return
     *                    something, either at the start of the current range if preferStart,
     *                    or the end of the current range if preferEnd. This function is often used
     *                    to find the approximate start and end indices of a known range, when
     *                    erring on the outside of that range is ok but erring on the inside of
     *                    the range causes points to be clipped.
     * @return The index of an approximate X match in the array
     */
    @VisibleForTesting
    int approximateBinarySearch(long searchX, int startIndex, int endIndex,
            boolean preferStart, int searchRange) {
        if (mData.isEmpty()) {
            return 0;
        }

        // See if we're already done (need to do this before calculating distances below, in case
        // searchX is so big or small we're in danger of overflow).

        long startValue = mData.get(startIndex).getX();
        if (searchX <= startValue) {
            return startIndex;
        }
        long endValue = mData.get(endIndex).getX();
        if (searchX >= endValue) {
            return endIndex;
        }
        if (endIndex - startIndex <= searchRange) {
            return preferStart ? startIndex : endIndex;
        }
        if (searchRange == 0 && endIndex - startIndex == 1) {
            long distanceToStart = searchX - startValue;
            long distanceToEnd = endValue - searchX;
            if (distanceToStart < distanceToEnd) {
                return startIndex;
            } else if (distanceToStart == distanceToEnd) {
                return preferStart ? startIndex : endIndex;
            } else {
                return endIndex;
            }
        }
        int mid = (startIndex + endIndex) / 2;
        long midX = mData.get(mid).getX();
        if (midX < searchX) {
            return approximateBinarySearch(searchX, mid, endIndex, preferStart, searchRange);
        } else if (midX > searchX) {
            return approximateBinarySearch(searchX, startIndex, mid, preferStart, searchRange);
        } else {
            return mid;
        }
    }

    public int getNumPoints() {
        return mData.size();
    }

    public boolean isEmpty() {
        return mData.isEmpty();
    }

    // Assume points are ordered
    public long getXMin() {
        return mData.get(0).getX();
    }

    // Assume points are ordered
    public long getXMax() {
        return mData.get(mData.size() - 1).getX();
    }

    public void clear() {
        mData.clear();
        mLabels.clear();
        mUnaddedLabels.clear();
    }

    public void setDisplayableLabels(List<Label> labels) {
        mLabels.clear();
        mUnaddedLabels.clear();
        for (Label label : labels) {
            if (!tryAddingLabel(label)) {
                mUnaddedLabels.add(label);
            }
        }
    }

    public void addLabel(Label label) {
        if (!tryAddingLabel(label)) {
            mUnaddedLabels.add(label);
        }
    }

    @VisibleForTesting
    boolean tryAddingLabel(Label label) {
        long timestamp = label.getTimeStamp();
        if (mData.isEmpty() || timestamp < getXMin() || timestamp > getXMax()) {
            return false;
        }
        int indexPrev = exactBinarySearch(timestamp, 0);
        int indexEnd = exactBinarySearch(timestamp, indexPrev);
        DataPoint start = mData.get(indexPrev);
        DataPoint end = mData.get(indexEnd);
        if (start.getX() == end.getX()) {
            mLabels.add(start);
        } else {
            double weight = (timestamp - start.getX()) / (end.getX() - start.getX()) * 1.0;
            mLabels.add(
                    new DataPoint(timestamp, start.getY() * weight + end.getY() * (1 - weight)));
        }
        return true;
    }

    public List<DataPoint> getLabelPoints() {
        return mLabels;
    }

    public void updateStats(List<StreamStat> stats) {
        mStats = stats;
    }

    public List<StreamStat> getStats() {
        return mStats;
    }

    public void throwAwayBefore(long throwawayThreshold) {
        throwAwayBetween(Long.MIN_VALUE, throwawayThreshold);
    }

    public void throwAwayAfter(long throwawayThreshold) {
        throwAwayBetween(throwawayThreshold, Long.MAX_VALUE);
    }

    public void throwAwayBetween(long throwAwayMinX, long throwAwayMaxX) {
        if (throwAwayMaxX <= throwAwayMinX) {
            return;
        }

        // This should be the index to the right of max
        int indexEnd = approximateBinarySearch(throwAwayMaxX, 0, mData.size() - 1, false, 1);
        int indexStart = approximateBinarySearch(throwAwayMinX, 0, mData.size() - 1, false, 1);

        // Only throw away in bulk once we reach a threshold, so that all the work is not done on
        // every iteration.
        if (indexEnd - indexStart < mThrowawayDataSizeThreshold) {
            return;
        }
        mData.subList(indexStart, indexEnd).clear();
    }

}