package com.camstream.app.encoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.camstream.app.R
import kotlin.math.max

/**
 * Dibuja el rótulo (nombre + cargo) como bitmap listo para superponer al
 * stream. El tamaño se escala con la altura del video para que se vea
 * igual en 480p que en 1080p.
 */
object OverlayFactory {

    const val STYLE_CLEAN = 0     // texto con sombra, sin fondo
    const val STYLE_BAR = 1       // barra translúcida con franja de acento
    const val STYLE_PILL = 2      // píldora sólida del color de acento
    const val STYLE_GRADIENT = 3  // píldora con el degradado de marca

    fun make(
        context: Context,
        name: String,
        role: String,
        textColor: Int,
        accentColor: Int,
        style: Int,
        streamHeight: Int,
    ): Bitmap {
        val quicksand = ResourcesCompat.getFont(context, R.font.quicksand)

        val nameSize = streamHeight * 0.055f
        val roleSize = streamHeight * 0.038f
        val hasRole = role.isNotBlank()

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = nameSize
            typeface = Typeface.create(quicksand, Typeface.BOLD)
        }
        val rolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            alpha = 230
            textSize = roleSize
            typeface = quicksand
        }
        if (style == STYLE_CLEAN) {
            val shadow = nameSize * 0.06f
            namePaint.setShadowLayer(shadow * 2, shadow, shadow, Color.BLACK)
            rolePaint.setShadowLayer(shadow * 2, shadow, shadow, Color.BLACK)
        }

        val nameWidth = namePaint.measureText(name)
        val roleWidth = if (hasRole) rolePaint.measureText(role) else 0f
        val padH = nameSize * 0.8f
        val padV = nameSize * 0.45f
        val lineGap = if (hasRole) nameSize * 0.25f else 0f
        val stripe = if (style == STYLE_BAR) nameSize * 0.35f else 0f

        val textHeight = nameSize + lineGap + (if (hasRole) roleSize else 0f)
        val width = (stripe + padH * 2 + max(nameWidth, roleWidth)).toInt() + 2
        val height = (padV * 2 + textHeight).toInt() + 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bg = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val corner = height / 2f
        when (style) {
            STYLE_BAR -> {
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(190, 10, 18, 22)
                }
                val r = nameSize * 0.2f
                canvas.drawRoundRect(bg, r, r, barPaint)
                val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
                canvas.drawRoundRect(RectF(0f, 0f, stripe, height.toFloat()), r, r, stripePaint)
                canvas.drawRect(RectF(stripe / 2, 0f, stripe, height.toFloat()), stripePaint)
            }
            STYLE_PILL -> {
                val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
                canvas.drawRoundRect(bg, corner, corner, pillPaint)
            }
            STYLE_GRADIENT -> {
                val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, height.toFloat(), width.toFloat(), 0f,
                        intArrayOf(0xFF1A8CB1.toInt(), 0xFF2AADA8.toInt(), 0xFF41DE8F.toInt()),
                        null, Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRoundRect(bg, corner, corner, gradPaint)
            }
        }

        val textX = stripe + padH
        var baseline = padV - namePaint.ascent()
        canvas.drawText(name, textX, baseline, namePaint)
        if (hasRole) {
            baseline += namePaint.descent() + lineGap - rolePaint.ascent()
            canvas.drawText(role, textX, baseline, rolePaint)
        }
        return bitmap
    }
}
