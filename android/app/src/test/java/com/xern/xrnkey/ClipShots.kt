package com.xern.xrnkey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.xern.xrnkey.clip.Clip

/** Made-up clips for the clipboard panel's pictures: texts, and two images with thumbnails. */
object ClipShots {
    val clips = listOf(
        Clip("", 7, image = "c0ffee.png", mime = "image/png"),
        Clip("Hẹn 7 giờ tối thứ Sáu ở quán cà phê cũ nhé, nhớ mang theo sách", 6, pinned = true),
        Clip("", 5, pinned = true, image = "beef.jpg", mime = "image/jpeg"),
        Clip("https://example.com/menu", 4),
        Clip("0901 234 567", 3),
        Clip("Thanks, see you tomorrow!", 2),
    )

    /** A made-up chat screenshot (tall, bubbles), and a made-up photo (wide, sky and hills). */
    fun thumbnail(clip: Clip): Bitmap? {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        return when (clip.image) {
            "c0ffee.png" -> Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                c.drawColor(0xFFF4F1EC.toInt())
                p.color = 0xFF2F6F9F.toInt()
                c.drawRect(0f, 0f, 180f, 36f, p)
                for (i in 0 until 6) {
                    val mine = i % 2 == 1
                    p.color = if (mine) 0xFFC4579D.toInt() else 0xFFFFFFFF.toInt()
                    val l = if (mine) 70f else 10f
                    c.drawRoundRect(l, 50f + i * 44f, l + 100f, 82f + i * 44f, 12f, 12f, p)
                }
            }
            "beef.jpg" -> Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                c.drawColor(0xFF8EC5E8.toInt())
                p.color = 0xFFF6D365.toInt()
                c.drawCircle(250f, 60f, 26f, p)
                p.color = 0xFF4F8A4B.toInt()
                c.drawOval(-60f, 120f, 220f, 300f, p)
                p.color = 0xFF3B6E3A.toInt()
                c.drawOval(120f, 140f, 400f, 320f, p)
            }
            else -> null
        }
    }
}
