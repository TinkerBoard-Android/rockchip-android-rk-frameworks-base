/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.location;

/**
 * This class represents the current state of the GNSS engine.
 * This class is used in conjunction with the {@link GnssStatusCallback}.
 */
public final class GnssStatus {
    /** Unknown constellation type. */
    public static final int CONSTELLATION_UNKNOWN = 0;
    /** Constellation type constant for GPS. */
    public static final int CONSTELLATION_GPS = 1;
    /** Constellation type constant for SBAS. */
    public static final int CONSTELLATION_SBAS = 2;
    /** Constellation type constant for Glonass. */
    public static final int CONSTELLATION_GLONASS = 3;
    /** Constellation type constant for QZSS. */
    public static final int CONSTELLATION_QZSS = 4;
    /** Constellation type constant for Beidou. */
    public static final int CONSTELLATION_BEIDOU = 5;
    /** Constellation type constant for Galileo. */
    public static final int CONSTELLATION_GALILEO = 6;

    // these must match the definitions in gps.h
    /** @hide */
    public static final int GNSS_SV_FLAGS_NONE = 0;
    /** @hide */
    public static final int GNSS_SV_FLAGS_HAS_EPHEMERIS_DATA = (1 << 0);
    /** @hide */
    public static final int GNSS_SV_FLAGS_HAS_ALMANAC_DATA = (1 << 1);
    /** @hide */
    public static final int GNSS_SV_FLAGS_USED_IN_FIX = (1 << 2);

    /** @hide */
    public static final int PRN_SHIFT_WIDTH = 3;

    /* These package private values are modified by the LocationManager class */
    /* package */ int[] mPrnWithFlags;
    /* package */ float[] mSnrs;
    /* package */ float[] mElevations;
    /* package */ float[] mAzimuths;
    /* package */ int[] mConstellationTypes;
    /* package */ int mSvCount;

    GnssStatus(int svCount, int[] prnWithFlags, float[] snrs, float[] elevations, float[] azimuths,
            int[] constellationTypes) {
        mSvCount = svCount;
        mPrnWithFlags = prnWithFlags;
        mSnrs = snrs;
        mElevations = elevations;
        mAzimuths = azimuths;
        mConstellationTypes = constellationTypes;
    }

    /**
     * Gets the total number of satellites in satellite list.
     */
    public int getNumSatellites() {
        return mSvCount;
    }

    /**
     * Retrieves the constellation type of the satellite at the specified position.
     * @param satIndex the index of the satellite in the list.
     */
    public int getConstellationType(int satIndex) {
        return mConstellationTypes[satIndex];
    }

    /**
     * Retrieves the pseudo-random number of the satellite at the specified position.
     * @param satIndex the index of the satellite in the list.
     */
    public int getPrn(int satIndex) {
        return mPrnWithFlags[satIndex] >> PRN_SHIFT_WIDTH;
    }

    /**
     * Retrieves the signal-noise ration of the satellite at the specified position.
     * @param satIndex the index of the satellite in the list.
     */
    public float getSnr(int satIndex) {
        return mSnrs[satIndex];
    }

    /**
     * Retrieves the elevation of the satellite at the specified position.
     * @param satIndex the index of the satellite in the list.
     */
    public float getElevation(int satIndex) {
        return 0f;
    }

    /**
     * Retrieves the azimuth the satellite at the specified position.
     * @param satIndex the index of the satellite in the list.
     */
    public float getAzimuth(int satIndex) {
        return mAzimuths[satIndex];
    }

    /**
     * Detects whether the satellite at the specified position has ephemeris data.
     * @param satIndex the index of the satellite in the list.
     */
    public boolean hasEphemeris(int satIndex) {
        return (mPrnWithFlags[satIndex] & GNSS_SV_FLAGS_HAS_EPHEMERIS_DATA) != 0;
    }

    /**
     * Detects whether the satellite at the specified position has almanac data.
     * @param satIndex the index of the satellite in the list.
     */
    public boolean hasAlmanac(int satIndex) {
        return (mPrnWithFlags[satIndex] & GNSS_SV_FLAGS_HAS_ALMANAC_DATA) != 0;
    }

    /**
     * Detects whether the satellite at the specified position is used in fix.
     * @param satIndex the index of the satellite in the list.
     */
    public boolean usedInFix(int satIndex) {
        return (mPrnWithFlags[satIndex] & GNSS_SV_FLAGS_USED_IN_FIX) != 0;
    }
}