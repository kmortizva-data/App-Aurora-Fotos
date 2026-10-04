package com.aurorafotos.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size

/** Read-only view of what a camera exposes to third-party apps. */
class CameraInfo(val id: String, val characteristics: CameraCharacteristics) {
    val facingBack: Boolean =
        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK

    val capabilities: IntArray =
        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

    val hasRaw: Boolean = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
    val hasManualSensor: Boolean = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
    val hasManualPostProcessing: Boolean =
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
    val isLogicalMultiCamera: Boolean =
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities

    val exposureRange: Range<Long>? = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val isoRange: Range<Int>? = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val maxAnalogIso: Int? = characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
    val maxFrameDurationNs: Long? = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
    val whiteLevel: Int = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
    val blackLevel: Int = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        ?.let { p -> (0 until 4).map { p.getOffsetForIndex(it % 2, it / 2) }.average().toInt() } ?: 64
    val minFocusDiopters: Float = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val focusCalibration: Int = characteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
        ?: CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
    val focalLengths: FloatArray = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
    val apertures: FloatArray = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0)
    val sensorOrientation: Int = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    val hardwareLevel: Int = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
    val awbModes: IntArray = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
    val physicalIds: Set<String> = characteristics.physicalCameraIds

    private val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val rawSizes: List<Size> = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList()
    val jpegSizes: List<Size> = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
    val previewSizes: List<Size> = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList() ?: emptyList()

    val largestRaw: Size? = rawSizes.maxByOrNull { it.width.toLong() * it.height }
    val largestJpeg: Size? = jpegSizes.maxByOrNull { it.width.toLong() * it.height }

    val maxExposureNs: Long = exposureRange?.upper ?: 0L
    val minExposureNs: Long = exposureRange?.lower ?: 0L

    fun clampExposure(ns: Long): Long {
        val r = exposureRange ?: return ns
        return ns.coerceIn(r.lower, r.upper)
    }

    fun clampIso(iso: Int): Int {
        val r = isoRange ?: return iso
        return iso.coerceIn(r.lower, r.upper)
    }

    fun hardwareLevelName(): String = when (hardwareLevel) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "?"
    }

    companion object {
        fun load(context: Context, id: String): CameraInfo {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return CameraInfo(id, cm.getCameraCharacteristics(id))
        }

        fun loadAll(context: Context): List<CameraInfo> {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return cm.cameraIdList.mapNotNull { id ->
                runCatching { CameraInfo(id, cm.getCameraCharacteristics(id)) }.getOrNull()
            }
        }

        /** Nearest Camera2 AWB preset for a colour temperature (0 = auto). */
        fun awbModeForKelvin(kelvin: Int, available: IntArray): Int {
            if (kelvin <= 0) return CameraMetadata.CONTROL_AWB_MODE_AUTO
            val table = listOf(
                CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to 2850,
                CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to 3200,
                CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to 4000,
                CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to 5500,
                CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to 6500,
                CameraMetadata.CONTROL_AWB_MODE_SHADE to 7500,
            ).filter { it.first in available }
            return table.minByOrNull { Math.abs(it.second - kelvin) }?.first
                ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
        }
    }
}
