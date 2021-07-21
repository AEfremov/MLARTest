package ru.efremov.mlartest

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat.NV21
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Scene
import com.google.firebase.FirebaseApp
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import ru.efremov.mlartest.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream

class MainActivity :
    AppCompatActivity(),
    Scene.OnUpdateListener
{

    private val TAG = MainActivity::class.java.simpleName

    private lateinit var _binding: ActivityMainBinding

    private var installRequested: Boolean = false
    private var session: Session? = null
    private var shouldConfigureSession = false
    private val messageSnackbarHelper = SnackbarHelper()
    private lateinit var cameraId: String
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val edgeDetector: EdgeDetector = EdgeDetector()

    private var videoUrl = "https://giftsolitaire.com/static/videotest.mp4"
    private var qrCodeDataObtained = false

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(_binding.root)

        displayRotationHelper = DisplayRotationHelper(this)

        _binding.arSceneView.scene.addOnUpdateListener(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                this, "Camera permissions are needed to run this application", Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onUpdate(frameTime: FrameTime?) {
        if (session == null) {
            return
        }

        val frame: Frame = _binding.arSceneView.arFrame ?: return

        val cameraImage = frame.tryAcquireCameraImage()

        if (cameraImage != null) {
            Log.d(
                "cameraImage",
                "${cameraImage.height} ${cameraImage.width} ${cameraImage.timestamp}"
            )

            if (cameraImage.format != ImageFormat.YUV_420_888) {
                throw IllegalArgumentException(
                    "Expected image in YUV_420_888 format, got format " + cameraImage.format
                )
            }

            if (!qrCodeDataObtained) {

                cameraId = session?.cameraConfig?.cameraId!!
                val rotationDegrees =
                    displayRotationHelper?.getCameraSensorToDisplayRotation(cameraId)
                val bitmapFromCameraImage = cameraImage.toBitmapExtended()
//                _binding.imageView.setImageBitmap(bitmapFromCameraImage)
                val visionImage =
                    InputImage.fromBitmap(bitmapFromCameraImage, rotationDegrees ?: 0)

                val scanner = BarcodeScanning.getClient(options)
                scanner.process(visionImage)
                    .addOnSuccessListener { barcodes ->
                        Log.d("addOnSuccessListener", barcodes.toString())
                        if (barcodes.isNotEmpty()) {
                            qrCodeDataObtained = true
                            for (barcode in barcodes) {
                                Log.e(
                                    "Log",
                                    "QR Code: " + barcode.displayValue
                                ) //Returns barcode value in a user-friendly format.
                                Log.e(
                                    "Log",
                                    "Raw Value: " + barcode.rawValue
                                ) //Returns barcode value as it was encoded in the barcode.
                                Log.e(
                                    "Log",
                                    "Code Type: " + barcode.valueType
                                ) //This will tell you the type of your barcode

                                val bounds = barcode.boundingBox
                                val corners = barcode.cornerPoints
                                val rawValue = barcode.rawValue

                                when (barcode.valueType) {
                                    Barcode.TYPE_URL -> {
                                        videoUrl = barcode.url?.url ?: ""

                                        val i = Intent(Intent.ACTION_VIEW)
                                        i.data = Uri.parse(videoUrl)
                                        startActivity(i)
                                    }
                                }
                            }
                        }
                    }
                    .addOnFailureListener {
                        it.printStackTrace()
                        Log.d("addOnFailureListener", it.message.toString())
                    }
                    .addOnCanceledListener {
                        Log.d("addOnCanceledListener", "cancel")
                    }
                    .addOnCompleteListener {
                        cameraImage.close()
                        Log.d("addOnCompleteListener", it.toString())
                    }
            }
        }

        try {
            cameraImage?.close()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun Image.toBitmapExtended(): Bitmap {
        val planes: Array<Image.Plane> = planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        // U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 75, out)

        val imageBytes = out.toByteArray()

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    fun Frame.tryAcquireCameraImage() = try {
        acquireCameraImage()
    } catch (e: NotYetAvailableException) {
        null
    } catch (e: Throwable) {
        throw e
    }

    private fun rotationDegreesToFirebaseRotation(rotationDegrees: Int): Int {
        return when (rotationDegrees) {
            0 -> Surface.ROTATION_0
            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> throw IllegalArgumentException("Not supported")
        }
    }

    private fun configureSession() {
        val config = Config(session)
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        session?.configure(config)
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                    }
                }

                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }

                session = Session(/* context = */this)
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: Exception) {
                message = "This device does not support AR"
                exception = e
            }

            if (message != null) {
                messageSnackbarHelper.showError(this, message)
                Log.e(TAG, "Exception creating session", exception)
                return
            }

            shouldConfigureSession = true
        }

        if (shouldConfigureSession) {
            configureSession()
            shouldConfigureSession = false
            _binding.arSceneView.setupSession(session)
        }

        try {
            session?.resume()
            _binding.arSceneView.resume()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this, "Camera not available. Please restart the app.")
            session = null
            return
        }
    }
}