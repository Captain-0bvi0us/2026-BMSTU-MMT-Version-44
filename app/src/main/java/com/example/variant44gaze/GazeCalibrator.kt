package com.example.variant44gaze

import android.content.Context
import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Калибратор направления взгляда → координаты экрана.
 *
 * До калибровки используется линейное отображение с симметричным усилением.
 * После 9-точечной калибровки строится полиномиальная регрессия 2-го порядка
 * по 6 коэффициентам для X и Y отдельно. Это компенсирует:
 *   - анатомическое смещение зрачка;
 *   - разную чувствительность по горизонтали и вертикали;
 *   - нелинейные искажения на краях экрана.
 *
 * Модель:
 *   screenX = a0 + a1*gx + a2*gy + a3*gx*gy + a4*gx^2 + a5*gy^2
 *   screenY = b0 + b1*gx + b2*gy + b3*gx*gy + b4*gx^2 + b5*gy^2
 * где (gx, gy) — нормированное направление взгляда из MediaPipe в [-1, 1].
 */
class GazeCalibrator(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Текущие коэффициенты регрессии. */
    @Volatile
    private var coeffsX: DoubleArray? = null

    @Volatile
    private var coeffsY: DoubleArray? = null

    /** Сэмплы текущей калибровки: (gx, gy) → (sx, sy). */
    private val samples = mutableListOf<CalibrationSample>()

    init {
        loadFromPrefs()
    }

    /** Точка цели в нормированных координатах экрана (0..1, 0..1). */
    data class CalibrationTarget(val nx: Float, val ny: Float)

    /** Одна точка калибровки: что система видела ↔ куда смотрел пользователь. */
    data class CalibrationSample(val gx: Float, val gy: Float, val sx: Float, val sy: Float)

    /** Стандартный набор из 9 точек в системе нормированных координат экрана. */
    val targets: List<CalibrationTarget> = listOf(
        CalibrationTarget(0.10f, 0.12f),
        CalibrationTarget(0.50f, 0.12f),
        CalibrationTarget(0.90f, 0.12f),
        CalibrationTarget(0.10f, 0.50f),
        CalibrationTarget(0.50f, 0.50f),
        CalibrationTarget(0.90f, 0.50f),
        CalibrationTarget(0.10f, 0.88f),
        CalibrationTarget(0.50f, 0.88f),
        CalibrationTarget(0.90f, 0.88f)
    )

    val isCalibrated: Boolean
        get() = coeffsX != null && coeffsY != null

    /** Сбросить текущую сессию набора сэмплов (не удаляя существующие коэффициенты). */
    fun beginCalibration() {
        samples.clear()
    }

    /**
     * Добавить сэмпл для калибровочной цели.
     *
     * @param target нормированная цель на экране.
     * @param gazeRaw усреднённое сырое направление взгляда за период удержания.
     * @param screenWidth ширина экрана в пикселях.
     * @param screenHeight высота экрана в пикселях.
     */
    fun addSample(
        target: CalibrationTarget,
        gazeRaw: PointF,
        screenWidth: Float,
        screenHeight: Float
    ) {
        val sx = target.nx * screenWidth
        val sy = target.ny * screenHeight
        samples.add(CalibrationSample(gazeRaw.x, gazeRaw.y, sx, sy))
    }

    /**
     * Завершить калибровку: вычислить коэффициенты регрессии и сохранить их.
     * Минимум 6 уникальных сэмплов нужно для устойчивого решения.
     */
    fun finishCalibration(): Boolean {
        if (samples.size < 6) return false
        val cx = solveLeastSquares(samples) { it.sx.toDouble() }
        val cy = solveLeastSquares(samples) { it.sy.toDouble() }
        if (cx == null || cy == null) return false
        coeffsX = cx
        coeffsY = cy
        saveToPrefs()
        return true
    }

    /** Полностью забыть калибровку (вернуться к линейному фолбэку). */
    fun clearCalibration() {
        coeffsX = null
        coeffsY = null
        samples.clear()
        prefs.edit().remove(KEY_COEFFS_X).remove(KEY_COEFFS_Y).apply()
    }

    /**
     * Преобразовать сырое направление взгляда в координаты экрана.
     */
    fun map(gazeRaw: PointF, screenWidth: Float, screenHeight: Float): PointF {
        val cx = coeffsX
        val cy = coeffsY
        if (cx != null && cy != null) {
            val sx = applyPolynomial(cx, gazeRaw.x, gazeRaw.y)
                .coerceIn(0.0, screenWidth.toDouble())
            val sy = applyPolynomial(cy, gazeRaw.x, gazeRaw.y)
                .coerceIn(0.0, screenHeight.toDouble())
            return PointF(sx.toFloat(), sy.toFloat())
        }
        // Линейный фолбэк: gx ∈ [-1, 1] → [0..screenWidth], gy → центр + наклон.
        // Дополнительный gain'ы вытягивают сигнал, потому что без калибровки
        // сырой gaze редко достигает экстремумов — без усиления точка
        // постоянно болтается у центра.
        val gx = (gazeRaw.x * LINEAR_GAIN_X).coerceIn(-1f, 1f)
        val gy = (gazeRaw.y * LINEAR_GAIN_Y).coerceIn(-1f, 1f)
        val sx = (0.5f + gx * 0.5f) * screenWidth
        val sy = (0.5f + gy * 0.5f) * screenHeight
        return PointF(sx.coerceIn(0f, screenWidth), sy.coerceIn(0f, screenHeight))
    }

    fun samplesCount(): Int = samples.size

    /**
     * Решает задачу наименьших квадратов A*c = y для квадратичной модели
     * (1, gx, gy, gx*gy, gx^2, gy^2). Возвращает вектор коэффициентов c[6].
     */
    private fun solveLeastSquares(
        data: List<CalibrationSample>,
        yExtractor: (CalibrationSample) -> Double
    ): DoubleArray? {
        val n = data.size
        if (n < 6) return null

        val a = Array(n) { DoubleArray(6) }
        val y = DoubleArray(n)
        for (i in 0 until n) {
            val s = data[i]
            val gx = s.gx.toDouble()
            val gy = s.gy.toDouble()
            a[i][0] = 1.0
            a[i][1] = gx
            a[i][2] = gy
            a[i][3] = gx * gy
            a[i][4] = gx * gx
            a[i][5] = gy * gy
            y[i] = yExtractor(s)
        }

        // Нормальные уравнения: (A^T A) c = A^T y, размер 6x6.
        val ata = Array(6) { DoubleArray(6) }
        val aty = DoubleArray(6)
        for (i in 0 until n) {
            for (r in 0 until 6) {
                aty[r] += a[i][r] * y[i]
                for (c in 0 until 6) {
                    ata[r][c] += a[i][r] * a[i][c]
                }
            }
        }
        // Регуляризация Тихонова — стабилизирует решение при коллинеарных сэмплах.
        for (i in 0 until 6) ata[i][i] += 1e-3
        return solveGauss(ata, aty)
    }

    private fun applyPolynomial(c: DoubleArray, gxF: Float, gyF: Float): Double {
        val gx = gxF.toDouble()
        val gy = gyF.toDouble()
        return c[0] + c[1] * gx + c[2] * gy + c[3] * gx * gy + c[4] * gx * gx + c[5] * gy * gy
    }

    /** Решает Ax = b методом Гаусса с частичным выбором главного элемента. */
    private fun solveGauss(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        val a = Array(n) { i -> matrix[i].copyOf() }
        val b = rhs.copyOf()
        for (k in 0 until n) {
            var pivot = k
            var maxAbs = kotlin.math.abs(a[k][k])
            for (i in k + 1 until n) {
                val v = kotlin.math.abs(a[i][k])
                if (v > maxAbs) {
                    maxAbs = v
                    pivot = i
                }
            }
            if (maxAbs < 1e-12) return null
            if (pivot != k) {
                val tmp = a[k]; a[k] = a[pivot]; a[pivot] = tmp
                val tb = b[k]; b[k] = b[pivot]; b[pivot] = tb
            }
            for (i in k + 1 until n) {
                val factor = a[i][k] / a[k][k]
                for (j in k until n) {
                    a[i][j] -= factor * a[k][j]
                }
                b[i] -= factor * b[k]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (j in i + 1 until n) s -= a[i][j] * x[j]
            x[i] = s / a[i][i]
        }
        return x
    }

    private fun saveToPrefs() {
        val cx = coeffsX ?: return
        val cy = coeffsY ?: return
        prefs.edit()
            .putString(KEY_COEFFS_X, JSONArray(cx.toList()).toString())
            .putString(KEY_COEFFS_Y, JSONArray(cy.toList()).toString())
            .apply()
    }

    private fun loadFromPrefs() {
        val xs = prefs.getString(KEY_COEFFS_X, null)
        val ys = prefs.getString(KEY_COEFFS_Y, null)
        if (xs.isNullOrEmpty() || ys.isNullOrEmpty()) return
        try {
            val ax = JSONArray(xs)
            val ay = JSONArray(ys)
            if (ax.length() != 6 || ay.length() != 6) return
            coeffsX = DoubleArray(6) { i -> ax.getDouble(i) }
            coeffsY = DoubleArray(6) { i -> ay.getDouble(i) }
        } catch (_: Exception) {
            coeffsX = null
            coeffsY = null
        }
    }

    /**
     * Оценка средней ошибки калибровки (px), считается после finishCalibration().
     */
    fun calibrationErrorPx(): Float {
        val cx = coeffsX ?: return -1f
        val cy = coeffsY ?: return -1f
        if (samples.isEmpty()) return -1f
        var total = 0.0
        for (s in samples) {
            val px = applyPolynomial(cx, s.gx, s.gy)
            val py = applyPolynomial(cy, s.gx, s.gy)
            val dx = px - s.sx
            val dy = py - s.sy
            total += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return (total / samples.size).toFloat().let { max(it, 0f) }
    }

    /** Сериализация состояния (для отладки/экспорта). */
    fun toDebugJson(): String {
        val o = JSONObject()
        o.put("calibrated", isCalibrated)
        o.put("samples", samples.size)
        coeffsX?.let { o.put("coeffsX", JSONArray(it.toList())) }
        coeffsY?.let { o.put("coeffsY", JSONArray(it.toList())) }
        return o.toString()
    }

    companion object {
        private const val PREFS_NAME = "gaze_calibration"
        private const val KEY_COEFFS_X = "coeffs_x"
        private const val KEY_COEFFS_Y = "coeffs_y"

        // Множители для линейного режима (когда калибровка ещё не выполнена).
        // X завышен сильнее, потому что горизонтальные движения глаз дают слабее
        // сырой сигнал, чем вертикальные.
        private const val LINEAR_GAIN_X = 1.8f
        private const val LINEAR_GAIN_Y = 1.4f
    }
}
