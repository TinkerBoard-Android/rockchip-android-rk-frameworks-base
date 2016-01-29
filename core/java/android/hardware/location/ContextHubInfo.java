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
package android.hardware.location;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * @hide
  */
@SystemApi
public class ContextHubInfo {
    private int mId;
    private String mName;
    private String mVendor;
    private String mToolchain;
    private int mPlatformVersion;
    private int mStaticSwVersion;
    private int mToolchainVersion;
    private float mPeakMips;
    private float mStoppedPowerDrawMw;
    private float mSleepPowerDrawMw;
    private float mPeakPowerDrawMw;

    private int[] mSupportedSensors;

    private MemoryRegion[] mMemoryRegions;

    public ContextHubInfo() {
    }

    /**
     * get the context hub unique identifer
     *
     * @return int - unique system wide identifier
     */
    public int getId() {
        return mId;
    }

    /**
     * set the context hub unique identifer
     *
     * @param id - unique system wide identifier for the hub
     */
    public void setId(int id) {
        mId = id;
    }

    /**
     * get a string as a hub name
     *
     * @return String - a name for the hub
     */
    public String getName() {
        return mName;
    }

    /**
     * set a string as the hub name
     *
     * @param String - the name for the hub
     */
    public void setName(String name) {
        mName = name;
    }

    /**
     * get a string as the vendor name
     *
     * @return String - a name for the vendor
     */
    public String getVendor() {
        return mVendor;
    }

    /**
     * set a string as the vendor name
     *
     * @param String - a name for the vendor
     */
    public void setVendor(String vendor) {
        mVendor = vendor;
    }

    /**
     * get tool chain string
     *
     * @return String - description of the tool chain
     */
    public String getToolchain() {
        return mToolchain;
    }

    /**
     * set tool chain string
     *
     * @param String - description of the tool chain
     */
    public void setToolchain(String toolchain) {
        mToolchain = toolchain;
    }

    /**
     * get platform version
     *
     * @return int - platform version number
     */
    public int getPlatformVersion() {
        return mPlatformVersion;
    }

    /**
     * set platform version
     *
     * @param platformVersion - platform version number
     */
    public void setPlatformVersion(int platformVersion) {
        mPlatformVersion = platformVersion;
    }

    /**
     * get static platform version number
     *
     * @return int - platform version number
     */
    public int getStaticSwVersion() {
        return mStaticSwVersion;
    }

    /**
     * set platform software version
     *
     * @param staticSwVersion - platform static s/w version number
     */
    public void setStaticSwVersion(int staticSwVersion) {
        mStaticSwVersion = staticSwVersion;
    }

    /**
     * get the tool chain version
     *
     * @return int - the tool chain version
     */
    public int getToolchainVersion() {
        return mToolchainVersion;
    }

    /**
     * set the tool chain version number
     *
     * @param toolchainVersion - tool chain version number
     */
    public void setToolchainVersion(int toolchainVersion) {
        mToolchainVersion = toolchainVersion;
    }

    /**
     * get the peak processing mips the hub can support
     *
     * @return float - peak MIPS that this hub can deliver
     */
    public float getPeakMips() {
        return mPeakMips;
    }

    /**
     * set the peak mips that this hub can support
     *
     * @param peakMips - peak mips this hub can deliver
     */
    public void setPeakMips(float peakMips) {
        mPeakMips = peakMips;
    }

    /**
     * get the stopped power draw in milliwatts
     * This assumes that the hub enter a stopped state - which is
     * different from the sleep state. Latencies on exiting the
     * sleep state are typically higher and expect to be in multiple
     * milliseconds.
     *
     * @return float - power draw by the hub in stopped state
     */
    public float getStoppedPowerDrawMw() {
        return mStoppedPowerDrawMw;
    }

    /**
     * Set the power consumed by the hub in stopped state
     *
     * @param stoppedPowerDrawMw - stopped power in milli watts
     */
    public void setStoppedPowerDrawMw(float stoppedPowerDrawMw) {
        mStoppedPowerDrawMw = stoppedPowerDrawMw;
    }

    /**
     * get the power draw of the hub in sleep mode. This assumes
     * that the hub supports a sleep mode in which the power draw is
     * lower than the power consumed when the hub is actively
     * processing. As a guideline, assume that the hub should be
     * able to enter sleep mode if it knows reliably on completion
     * of some task that the next interrupt/scheduled work item is
     * at least 250 milliseconds later.
     *
     * @return float - sleep power draw in milli watts
     */
    public float getSleepPowerDrawMw() {
        return mSleepPowerDrawMw;
    }

    /**
     * Set the sleep power draw in milliwatts
     *
     * @param sleepPowerDrawMw - sleep power draw in milliwatts.
     */
    public void setSleepPowerDrawMw(float sleepPowerDrawMw) {
        mSleepPowerDrawMw = sleepPowerDrawMw;
    }

    /**
     * get the peak powe draw of the hub. This is the power consumed
     * by the hub at maximum load.
     *
     * @return float - peak power draw
     */
    public float getPeakPowerDrawMw() {
        return mPeakPowerDrawMw;
    }

    /**
     * set the peak power draw of the hub
     *
     * @param peakPowerDrawMw - peak power draw of the hub in
     *                        milliwatts.
     */
    public void setPeakPowerDrawMw(float peakPowerDrawMw) {
        mPeakPowerDrawMw = peakPowerDrawMw;
    }

    /**
     * get the sensors supported by this hub
     *
     * @return int[] - all the supported sensors on this hub
     *
     * @see ContextHubManager
     */
    public int[] getSupportedSensors() {
        return Arrays.copyOf(mSupportedSensors, mSupportedSensors.length);
    }

    /**
     * get the various memory regions on this hub
     *
     * @return MemoryRegion[] - all the memory regions on this hub
     *
     * @see MemoryRegion
     */
    public MemoryRegion[] getMemoryRegions() {
        return Arrays.copyOf(mMemoryRegions, mMemoryRegions.length);
    }

    /**
     * set the supported sensors on this hub
     *
     * @param supportedSensors - supported sensors on this hub
     */
    public void setSupportedSensors(int[] supportedSensors) {
        mSupportedSensors = Arrays.copyOf(supportedSensors, supportedSensors.length);
    }

    /**
     * set memory regions for this hub
     *
     * @param memoryRegions - memory regions information
     *
     * @see MemoryRegion
     */
    public void setMemoryRegions(MemoryRegion[] memoryRegions) {
        mMemoryRegions = Arrays.copyOf(memoryRegions, memoryRegions.length);
    }

    private ContextHubInfo(Parcel in) {
        mId = in.readInt();
        mName = in.readString();
        mVendor = in.readString();
        mToolchain = in.readString();
        mPlatformVersion = in.readInt();
        mToolchainVersion = in.readInt();
        mStaticSwVersion = in.readInt();
        mPeakMips = in.readFloat();
        mStoppedPowerDrawMw = in.readFloat();
        mSleepPowerDrawMw = in.readFloat();
        mPeakPowerDrawMw = in.readFloat();

        int numSupportedSensors = in.readInt();
        mSupportedSensors = new int[numSupportedSensors];
        in.readIntArray(mSupportedSensors);
        mMemoryRegions = in.createTypedArray(MemoryRegion.CREATOR);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mId);
        out.writeString(mName);
        out.writeString(mVendor);
        out.writeString(mToolchain);
        out.writeInt(mPlatformVersion);
        out.writeInt(mToolchainVersion);
        out.writeInt(mStaticSwVersion);
        out.writeFloat(mPeakMips);
        out.writeFloat(mStoppedPowerDrawMw);
        out.writeFloat(mSleepPowerDrawMw);
        out.writeFloat(mPeakPowerDrawMw);

        out.writeInt(mSupportedSensors.length);
        out.writeIntArray(mSupportedSensors);
        out.writeTypedArray(mMemoryRegions, flags);
    }

    public static final Parcelable.Creator<ContextHubInfo> CREATOR
            = new Parcelable.Creator<ContextHubInfo>() {
        public ContextHubInfo createFromParcel(Parcel in) {
            return new ContextHubInfo(in);
        }

        public ContextHubInfo[] newArray(int size) {
            return new ContextHubInfo[size];
        }
    };
}