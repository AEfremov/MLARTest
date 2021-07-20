package ru.efremov.mlartest

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.core.R
import com.google.ar.core.exceptions.*
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Scene
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.firebase.FirebaseApp
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import ru.efremov.mlartest.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

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

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    fun Frame.tryAcquireCameraImage() = try {
        acquireCameraImage()
    } catch (e: NotYetAvailableException) {
        null
    } catch (e: Throwable) {
        throw e
    }

    companion object {

    }

    override fun onUpdate(frameTime: FrameTime?) {
        if (session == null) {
            return
        }

        val frame: Frame = _binding.arSceneView.arFrame ?: return

        val cameraImage = frame.tryAcquireCameraImage()
        if (cameraImage != null) {
            Log.d("cameraImage", "${cameraImage.height} ${cameraImage.width} ${cameraImage.timestamp}")

            if (cameraImage.format != ImageFormat.YUV_420_888) {
                throw IllegalArgumentException(
                    "Expected image in YUV_420_888 format, got format " + cameraImage.format
                )
            }

            val processedImageBytesGrayscale: ByteBuffer =
                edgeDetector.detect(
                    cameraImage.width,
                    cameraImage.height,
                    cameraImage.planes[0].rowStride,
                    cameraImage.planes[0].buffer
                )

            val bitmap = Bitmap.createBitmap(
                cameraImage.width,
                cameraImage.height,
                Bitmap.Config.ALPHA_8
            )
            processedImageBytesGrayscale.rewind()
            bitmap.copyPixelsFromBuffer(processedImageBytesGrayscale)

            cameraId = session?.cameraConfig?.cameraId!!
            val rotationDegrees = displayRotationHelper?.getCameraSensorToDisplayRotation(cameraId)
//            val visionImage = InputImage.fromBitmap(bitmap, rotationDegrees!!)
            val visionImage = InputImage.fromBitmap(bitmap, 0)
            val scanner = BarcodeScanning.getClient(options)

            scanner.process(visionImage)
                .addOnSuccessListener { barcodes ->
                    Log.d("addOnSuccessListener", barcodes.toString())
                }
                .addOnFailureListener {
                    it.printStackTrace()
                    Log.d("addOnFailureListener", it.message.toString())
                }
                .addOnCanceledListener {
                    Log.d("addOnCanceledListener", "cancel")
                }
                .addOnCompleteListener {
                    Log.d("addOnCompleteListener", it.toString())
                }

            try {
                cameraImage.close()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
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