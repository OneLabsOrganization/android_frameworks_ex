/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.ex.camera2.portability;

import static android.hardware.camera2.CaptureRequest.*;

import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.params.MeteringRectangle;
import android.util.Range;

import com.android.ex.camera2.portability.CameraCapabilities.FlashMode;
import com.android.ex.camera2.portability.CameraCapabilities.FocusMode;
import com.android.ex.camera2.portability.CameraCapabilities.SceneMode;
import com.android.ex.camera2.portability.CameraCapabilities.WhiteBalance;
import com.android.ex.camera2.portability.debug.Log;
import com.android.ex.camera2.utils.Camera2RequestSettingsSet;

import java.util.List;
import java.util.Objects;

/**
 * The subclass of {@link CameraSettings} for Android Camera 2 API.
 */
public class AndroidCamera2Settings extends CameraSettings {
    private static final Log.Tag TAG = new Log.Tag("AndCam2Set");

    private final Builder mTemplateSettings;
    private final Rect mActiveArray;
    private final Camera2RequestSettingsSet mRequestSettings;

    /**
     * Create a settings representation that answers queries of unspecified
     * options in the same way as the provided template would.
     *
     * <p>The default settings provided by the given template are only ever used
     * for reporting back to the client app (i.e. when it queries an option
     * it didn't explicitly set first). {@link Camera2RequestSettingsSet}s
     * generated by an instance of this class will have any settings not
     * modified using one of that instance's mutators forced to default, so that
     * their effective values when submitting a capture request will be those of
     * the template that is provided to the camera framework at that time.</p>
     *
     * @param camera Device from which to draw default settings.
     * @param template Specific template to use for the defaults.
     * @param activeArray Boundary coordinates of the sensor's active array.
     * @param preview Dimensions of preview streams.
     * @param photo Dimensions of captured images.
     *
     * @throws CameraAccessException Upon internal framework/driver failure.
     */
    public AndroidCamera2Settings(CameraDevice camera, int template, Rect activeArray,
                                  Size preview, Size photo) throws CameraAccessException {
        mTemplateSettings = camera.createCaptureRequest(template);
        mActiveArray = activeArray;
        mRequestSettings = new Camera2RequestSettingsSet();

        Range<Integer> previewFpsRange = mTemplateSettings.get(CONTROL_AE_TARGET_FPS_RANGE);
        if (previewFpsRange != null) {
            setPreviewFpsRange(previewFpsRange.getLower(), previewFpsRange.getUpper());
        }
        setPreviewSize(preview);
        // TODO: mCurrentPreviewFormat
        setPhotoSize(photo);
        mJpegCompressQuality = queryTemplateDefaultOrMakeOneUp(JPEG_QUALITY, (byte) 0);
        // TODO: mCurrentPhotoFormat
        // TODO: mCurrentZoomRatio
        mCurrentZoomRatio = 1.0f;
        // TODO: mCurrentZoomIndex
        mExposureCompensationIndex =
                queryTemplateDefaultOrMakeOneUp(CONTROL_AE_EXPOSURE_COMPENSATION, 0);

        mCurrentFlashMode = flashModeFromRequest();
        Integer currentFocusMode = mTemplateSettings.get(CONTROL_AF_MODE);
        if (currentFocusMode != null) {
            mCurrentFocusMode = AndroidCamera2Capabilities.focusModeFromInt(currentFocusMode);
        }
        Integer currentSceneMode = mTemplateSettings.get(CONTROL_SCENE_MODE);
        if (currentSceneMode != null) {
            mCurrentSceneMode = AndroidCamera2Capabilities.sceneModeFromInt(currentSceneMode);
        }
        Integer whiteBalance = mTemplateSettings.get(CONTROL_AWB_MODE);
        if (whiteBalance != null) {
            mWhiteBalance = AndroidCamera2Capabilities.whiteBalanceFromInt(whiteBalance);
        }

        mVideoStabilizationEnabled = queryTemplateDefaultOrMakeOneUp(
                        CONTROL_VIDEO_STABILIZATION_MODE, CONTROL_VIDEO_STABILIZATION_MODE_OFF) ==
                CONTROL_VIDEO_STABILIZATION_MODE_ON;
        mAutoExposureLocked = queryTemplateDefaultOrMakeOneUp(CONTROL_AE_LOCK, false);
        mAutoWhiteBalanceLocked = queryTemplateDefaultOrMakeOneUp(CONTROL_AWB_LOCK, false);
        // TODO: mRecordingHintEnabled
        // TODO: mGpsData
        android.util.Size exifThumbnailSize = mTemplateSettings.get(JPEG_THUMBNAIL_SIZE);
        if (exifThumbnailSize != null) {
            mExifThumbnailSize =
                    new Size(exifThumbnailSize.getWidth(), exifThumbnailSize.getHeight());
        }
    }

    public AndroidCamera2Settings(AndroidCamera2Settings other) {
        super(other);
        mTemplateSettings = other.mTemplateSettings;
        mActiveArray = other.mActiveArray;
        mRequestSettings = new Camera2RequestSettingsSet(other.mRequestSettings);
    }

    @Override
    public CameraSettings copy() {
        return new AndroidCamera2Settings(this);
    }

    private <T> T queryTemplateDefaultOrMakeOneUp(Key<T> key, T defaultDefault) {
        T val = mTemplateSettings.get(key);
        if (val != null) {
            return val;
        } else {
            // Spoof the default so matchesTemplateDefault excludes this key from generated sets.
            // This approach beats a simple sentinel because it provides basic boolean support.
            mTemplateSettings.set(key, defaultDefault);
            return defaultDefault;
        }
    }

    private FlashMode flashModeFromRequest() {
        Integer autoExposure = mTemplateSettings.get(CONTROL_AE_MODE);
        if (autoExposure != null) {
            switch (autoExposure) {
                case CONTROL_AE_MODE_ON:
                    return FlashMode.OFF;
                case CONTROL_AE_MODE_ON_AUTO_FLASH:
                    return FlashMode.AUTO;
                case CONTROL_AE_MODE_ON_ALWAYS_FLASH: {
                    if (mTemplateSettings.get(FLASH_MODE) == FLASH_MODE_TORCH) {
                        return FlashMode.TORCH;
                    } else {
                        return FlashMode.ON;
                    }
                }
                case CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE:
                    return FlashMode.RED_EYE;
            }
        }
        return null;
    }

    private boolean matchesTemplateDefault(Key<?> setting) {
        if (setting == CONTROL_AE_REGIONS) {
            return mMeteringAreas.size() == 0;
        } else if (setting == CONTROL_AF_REGIONS) {
            return mFocusAreas.size() == 0;
        } else if (setting == CONTROL_AE_TARGET_FPS_RANGE) {
            Range defaultFpsRange = mTemplateSettings.get(CONTROL_AE_TARGET_FPS_RANGE);
            return (mPreviewFpsRangeMin == 0 && mPreviewFpsRangeMax == 0) ||
                    (defaultFpsRange != null && mPreviewFpsRangeMin == defaultFpsRange.getLower() &&
                            mPreviewFpsRangeMax == defaultFpsRange.getUpper());
        } else if (setting == JPEG_QUALITY) {
            return Objects.equals(mJpegCompressQuality,
                    mTemplateSettings.get(JPEG_QUALITY));
        } else if (setting == CONTROL_AE_EXPOSURE_COMPENSATION) {
            return Objects.equals(mExposureCompensationIndex,
                    mTemplateSettings.get(CONTROL_AE_EXPOSURE_COMPENSATION));
        } else if (setting == CONTROL_VIDEO_STABILIZATION_MODE) {
            Integer videoStabilization = mTemplateSettings.get(CONTROL_VIDEO_STABILIZATION_MODE);
            return (videoStabilization != null &&
                    (mVideoStabilizationEnabled && videoStabilization ==
                            CONTROL_VIDEO_STABILIZATION_MODE_ON) ||
                    (!mVideoStabilizationEnabled && videoStabilization ==
                            CONTROL_VIDEO_STABILIZATION_MODE_OFF));
        } else if (setting == CONTROL_AE_LOCK) {
            return Objects.equals(mAutoExposureLocked, mTemplateSettings.get(CONTROL_AE_LOCK));
        } else if (setting == CONTROL_AWB_LOCK) {
            return Objects.equals(mAutoWhiteBalanceLocked, mTemplateSettings.get(CONTROL_AWB_LOCK));
        } else if (setting == JPEG_THUMBNAIL_SIZE) {
            android.util.Size defaultThumbnailSize = mTemplateSettings.get(JPEG_THUMBNAIL_SIZE);
            return (mExifThumbnailSize.width() == 0 && mExifThumbnailSize.height() == 0) ||
                    (defaultThumbnailSize != null &&
                            mExifThumbnailSize.width() == defaultThumbnailSize.getWidth() &&
                            mExifThumbnailSize.height() == defaultThumbnailSize.getHeight());
        }
        Log.w(TAG, "Settings implementation checked default of unhandled option key");
        // Since this class isn't equipped to handle it, claim it matches the default to prevent
        // updateRequestSettingOrForceToDefault from going with the user-provided preference
        return true;
    }

    private <T> void updateRequestSettingOrForceToDefault(Key<T> setting, T possibleChoice) {
        mRequestSettings.set(setting, matchesTemplateDefault(setting) ? null : possibleChoice);
    }

    public Camera2RequestSettingsSet getRequestSettings() {
        updateRequestSettingOrForceToDefault(CONTROL_AE_REGIONS,
                legacyAreasToMeteringRectangles(mMeteringAreas));
        updateRequestSettingOrForceToDefault(CONTROL_AF_REGIONS,
                legacyAreasToMeteringRectangles(mFocusAreas));
        updateRequestSettingOrForceToDefault(CONTROL_AE_TARGET_FPS_RANGE,
                new Range(mPreviewFpsRangeMin, mPreviewFpsRangeMax));
        // TODO: mCurrentPreviewFormat
        updateRequestSettingOrForceToDefault(JPEG_QUALITY, mJpegCompressQuality);
        // TODO: mCurrentPhotoFormat
        // TODO: mCurrentZoomRatio
        // TODO: mCurrentZoomIndex
        updateRequestSettingOrForceToDefault(CONTROL_AE_EXPOSURE_COMPENSATION,
                mExposureCompensationIndex);
        updateRequestFlashMode();
        updateRequestFocusMode();
        updateRequestSceneMode();
        updateRequestWhiteBalance();
        updateRequestSettingOrForceToDefault(CONTROL_VIDEO_STABILIZATION_MODE,
                mVideoStabilizationEnabled ?
                        CONTROL_VIDEO_STABILIZATION_MODE_ON : CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        // OIS shouldn't be on if software video stabilization is.
        mRequestSettings.set(LENS_OPTICAL_STABILIZATION_MODE,
                mVideoStabilizationEnabled ? LENS_OPTICAL_STABILIZATION_MODE_OFF :
                        null);
        updateRequestSettingOrForceToDefault(CONTROL_AE_LOCK, mAutoExposureLocked);
        updateRequestSettingOrForceToDefault(CONTROL_AWB_LOCK, mAutoWhiteBalanceLocked);
        // TODO: mRecordingHintEnabled
        // TODO: mGpsData
        updateRequestSettingOrForceToDefault(JPEG_THUMBNAIL_SIZE,
                new android.util.Size(
                        mExifThumbnailSize.width(), mExifThumbnailSize.height()));

        return mRequestSettings;
    }

    private MeteringRectangle[] legacyAreasToMeteringRectangles(
            List<android.hardware.Camera.Area> reference) {
        MeteringRectangle[] transformed = null;
        if (reference.size() > 0) {

            transformed = new MeteringRectangle[reference.size()];
            for (int index = 0; index < reference.size(); ++index) {
                android.hardware.Camera.Area source = reference.get(index);
                Rect rectangle = source.rect;

                // Old API coordinates were [-1000,1000]; new ones are [0,ACTIVE_ARRAY_SIZE).
                double oldLeft = (rectangle.left + 1000) / 2000.0;
                double oldTop = (rectangle.top + 1000) / 2000.0;
                double oldRight = (rectangle.right + 1000) / 2000.0;
                double oldBottom = (rectangle.bottom + 1000) / 2000.0;
                int left = toIntConstrained( mActiveArray.width() * oldLeft + mActiveArray.left,
                        0, mActiveArray.width() - 1);
                int top = toIntConstrained( mActiveArray.height() * oldTop + mActiveArray.top,
                        0, mActiveArray.height() - 1);
                int right = toIntConstrained( mActiveArray.width() * oldRight + mActiveArray.left,
                        0, mActiveArray.width() - 1);
                int bottom = toIntConstrained( mActiveArray.height() * oldBottom + mActiveArray.top,
                        0, mActiveArray.height() - 1);
                transformed[index] = new MeteringRectangle(left, top, right - left, bottom - top,
                        source.weight);
            }
        }
        return transformed;
    }

    private int toIntConstrained(double original, int min, int max) {
        original = Math.max(original, min);
        original = Math.min(original, max);
        return (int) original;
    }

    private void updateRequestFlashMode() {
        Integer aeMode = null;
        Integer flashMode = null;
        if (mCurrentFlashMode != null) {
            switch (mCurrentFlashMode) {
                case AUTO: {
                    aeMode = CONTROL_AE_MODE_ON_AUTO_FLASH;
                    break;
                }
                case OFF: {
                    aeMode = CONTROL_AE_MODE_ON;
                    flashMode = FLASH_MODE_OFF;
                    break;
                }
                case ON: {
                    aeMode = CONTROL_AE_MODE_ON_ALWAYS_FLASH;
                    flashMode = FLASH_MODE_SINGLE;
                    break;
                }
                case TORCH: {
                    flashMode = FLASH_MODE_TORCH;
                    break;
                }
                case RED_EYE: {
                    aeMode = CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE;
                    break;
                }
                default: {
                    Log.w(TAG, "Unable to convert to API 2 flash mode: " + mCurrentFlashMode);
                    break;
                }
            }
        }
        mRequestSettings.set(CONTROL_AE_MODE, aeMode);
        mRequestSettings.set(FLASH_MODE, flashMode);
    }

    private void updateRequestFocusMode() {
        Integer mode = null;
        if (mCurrentFocusMode != null) {
            switch (mCurrentFocusMode) {
                case AUTO: {
                    mode = CONTROL_AF_MODE_AUTO;
                    break;
                }
                case CONTINUOUS_PICTURE: {
                    mode = CONTROL_AF_MODE_CONTINUOUS_PICTURE;
                    break;
                }
                case CONTINUOUS_VIDEO: {
                    mode = CONTROL_AF_MODE_CONTINUOUS_VIDEO;
                    break;
                }
                case EXTENDED_DOF: {
                    mode = CONTROL_AF_MODE_EDOF;
                    break;
                }
                case FIXED: {
                    mode = CONTROL_AF_MODE_OFF;
                    break;
                }
                // TODO: We cannot support INFINITY
                case MACRO: {
                    mode = CONTROL_AF_MODE_MACRO;
                    break;
                }
                default: {
                    Log.w(TAG, "Unable to convert to API 2 focus mode: " + mCurrentFocusMode);
                    break;
                }
            }
        }
        mRequestSettings.set(CONTROL_AF_MODE, mode);
    }

    private void updateRequestSceneMode() {
        Integer mode = null;
        if (mCurrentSceneMode != null) {
            switch (mCurrentSceneMode) {
                case AUTO: {
                    mode = CONTROL_SCENE_MODE_DISABLED;
                    break;
                }
                case ACTION: {
                    mode = CONTROL_SCENE_MODE_ACTION;
                    break;
                }
                case BARCODE: {
                    mode = CONTROL_SCENE_MODE_BARCODE;
                    break;
                }
                case BEACH: {
                    mode = CONTROL_SCENE_MODE_BEACH;
                    break;
                }
                case CANDLELIGHT: {
                    mode = CONTROL_SCENE_MODE_CANDLELIGHT;
                    break;
                }
                case FIREWORKS: {
                    mode = CONTROL_SCENE_MODE_FIREWORKS;
                    break;
                }
                // TODO: We cannot support HDR
                case LANDSCAPE: {
                    mode = CONTROL_SCENE_MODE_LANDSCAPE;
                    break;
                }
                case NIGHT: {
                    mode = CONTROL_SCENE_MODE_NIGHT;
                    break;
                }
                // TODO: We cannot support NIGHT_PORTRAIT
                case PARTY: {
                    mode = CONTROL_SCENE_MODE_PARTY;
                    break;
                }
                case PORTRAIT: {
                    mode = CONTROL_SCENE_MODE_PORTRAIT;
                    break;
                }
                case SNOW: {
                    mode = CONTROL_SCENE_MODE_SNOW;
                    break;
                }
                case SPORTS: {
                    mode = CONTROL_SCENE_MODE_SPORTS;
                    break;
                }
                case STEADYPHOTO: {
                    mode = CONTROL_SCENE_MODE_STEADYPHOTO;
                    break;
                }
                case SUNSET: {
                    mode = CONTROL_SCENE_MODE_SUNSET;
                    break;
                }
                case THEATRE: {
                    mode = CONTROL_SCENE_MODE_THEATRE;
                    break;
                }
                default: {
                    Log.w(TAG, "Unable to convert to API 2 scene mode: " + mCurrentSceneMode);
                    break;
                }
            }
        }
        mRequestSettings.set(CONTROL_SCENE_MODE, mode);
    }

    private void updateRequestWhiteBalance() {
        Integer mode = null;
        if (mWhiteBalance != null) {
            switch (mWhiteBalance) {
                case AUTO: {
                    mode = CONTROL_AWB_MODE_AUTO;
                    break;
                }
                case CLOUDY_DAYLIGHT: {
                    mode = CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
                    break;
                }
                case DAYLIGHT: {
                    mode = CONTROL_AWB_MODE_DAYLIGHT;
                    break;
                }
                case FLUORESCENT: {
                    mode = CONTROL_AWB_MODE_FLUORESCENT;
                    break;
                }
                case INCANDESCENT: {
                    mode = CONTROL_AWB_MODE_INCANDESCENT;
                    break;
                }
                case SHADE: {
                    mode = CONTROL_AWB_MODE_SHADE;
                    break;
                }
                case TWILIGHT: {
                    mode = CONTROL_AWB_MODE_TWILIGHT;
                    break;
                }
                case WARM_FLUORESCENT: {
                    mode = CONTROL_AWB_MODE_WARM_FLUORESCENT;
                    break;
                }
                default: {
                    Log.w(TAG, "Unable to convert to API 2 white balance: " + mWhiteBalance);
                    break;
                }
            }
        }
        mRequestSettings.set(CONTROL_AWB_MODE, mode);
    }
}
