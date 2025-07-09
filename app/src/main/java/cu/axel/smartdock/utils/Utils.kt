package cu.axel.smartdock.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Display
import android.view.WindowManager

object Utils {
    var shouldPlayChargeComplete = false
    var startupTime: Long = 0

    fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    fun getCircularBitmap(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null

        //Copy the bitmap to avoid software rendering issues
        val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.recycle()
        val result =
            Bitmap.createBitmap(bitmapCopy.width, bitmapCopy.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val color = -0xbdbdbe
        val paint = Paint()
        val rect = Rect(0, 0, bitmapCopy.width, bitmapCopy.height)
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = color
        canvas.drawCircle(
            (bitmapCopy.width / 2).toFloat(),
            (bitmapCopy.height / 2).toFloat(),
            (bitmap.width / 2).toFloat(),
            paint
        )
        paint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_IN))
        canvas.drawBitmap(bitmapCopy, rect, rect, paint)
        bitmapCopy.recycle()
        return result
    }

    fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        var bitmap: Bitmap? = null
        val contentResolver = context.contentResolver
        try {
            bitmap = if (Build.VERSION.SDK_INT < 28) {
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            } else {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            }
        } catch (_: Exception) {
        }
        return bitmap
    }

    fun getBatteryDrawable(level: Int, plugged: Boolean): Int {
        return 0
    }

    fun makeWindowParams(
        width: Int, height: Int, context: Context,
        secondary: Boolean = false
    ): WindowManager.LayoutParams {

        val displayId =
            if (secondary) DeviceUtils.getSecondaryDisplay(context).displayId else Display.DEFAULT_DISPLAY

        val displayWidth = DeviceUtils.getDisplayMetrics(context, displayId).widthPixels
        val displayHeight = DeviceUtils.getDisplayMetrics(context, displayId).heightPixels
        val layoutParams = WindowManager.LayoutParams()
        layoutParams.format = PixelFormat.TRANSLUCENT
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        layoutParams.type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
        layoutParams.width = displayWidth.coerceAtMost(width)
        layoutParams.height = displayHeight.coerceAtMost(height)
        return layoutParams
    }

    fun solve(expression: String): Double {
        if (expression.contains("+")) return expression.split("\\+".toRegex())
            .dropLastWhile { it.isEmpty() }
            .toTypedArray()[0].toDouble() + expression.split("\\+".toRegex())
            .dropLastWhile { it.isEmpty() }
            .toTypedArray()[1].toDouble() else if (expression.contains("-")) return expression.split(
            "\\-".toRegex()
        ).dropLastWhile { it.isEmpty() }
            .toTypedArray()[0].toDouble() - expression.split("\\-".toRegex())
            .dropLastWhile { it.isEmpty() }.toTypedArray()[1].toDouble()
        if (expression.contains("/")) return expression.split("\\/".toRegex())
            .dropLastWhile { it.isEmpty() }
            .toTypedArray()[0].toDouble() / expression.split("\\/".toRegex())
            .dropLastWhile { it.isEmpty() }.toTypedArray()[1].toDouble()
        return if (expression.contains("*")) expression.split("\\*".toRegex())
            .dropLastWhile { it.isEmpty() }
            .toTypedArray()[0].toDouble() * expression.split("\\*".toRegex())
            .dropLastWhile { it.isEmpty() }.toTypedArray()[1].toDouble() else 0.0
    }
}
