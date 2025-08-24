package com.example.kingassistant

import android.app.Service
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var container: ViewGroup
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 1000L // 1秒刷新
    private lateinit var tflite: Interpreter

    private val gameHistory = mutableListOf<GameAction>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_widget, null)
        container = floatingView.findViewById(R.id.container) as ViewGroup
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 加载本地TFLite模型
        tflite = loadModel()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager.addView(floatingView, params)

        // 可拖动悬浮窗
        floatingView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        startVoiceRecognition()
        startRealtimeRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        windowManager.removeView(floatingView)
    }

    // ===================== 实时刷新 =====================
    private fun startRealtimeRefresh() {
        handler.post(object : Runnable {
            override fun run() {
                captureAndUpdate()
                handler.postDelayed(this, refreshInterval)
            }
        })
    }

    private fun captureAndUpdate() {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val decision = analyzeScreen(bitmap)
        showSuggestion(decision)
        showEnemyPositions(decision)
        recordPlayerAction("占位动作", bitmap)
    }

    // ===================== AI策略占位 =====================
    private fun analyzeScreen(bitmap: Bitmap): Decision {
        val input = FloatArray(11) { 0.5f } // 占位特征
        val strategyIndex = predictStrategy(tflite, input)
        val suggestion = when(strategyIndex) {
            0 -> "进攻"
            1 -> "防守"
            else -> "推塔"
        }
        return Decision(
            suggestion = suggestion,
            enemies = listOf(
                Enemy("敌方1", x = 200, y = 400),
                Enemy("敌方2", x = 600, y = 800)
            )
        )
    }

    private fun showSuggestion(decision: Decision) {
        Toast.makeText(this, decision.suggestion, Toast.LENGTH_SHORT).show()
    }

    private fun showEnemyPositions(decision: Decision) {
        container.removeAllViews()
        val mainIcon = ImageView(this)
        mainIcon.setImageDrawable(getDrawable(R.mipmap.ic_launcher))
        mainIcon.layoutParams = ViewGroup.LayoutParams(60, 60)
        container.addView(mainIcon)

        for (enemy in decision.enemies) {
            val icon = ImageView(this)
            icon.setImageDrawable(getDrawable(R.drawable.enemy_icon))
            val params = ViewGroup.LayoutParams(50, 50)
            icon.layoutParams = params
            icon.x = enemy.x.toFloat()
            icon.y = enemy.y.toFloat()
            container.addView(icon)
        }
    }

    // ===================== 语音识别 =====================
    private fun startVoiceRecognition() {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.let {
                    val command = it[0]
                    if (command.contains("灵宝")) {
                        val suggestion = "语音建议: 推塔或打野"
                        showSuggestion(Decision(suggestion, emptyList()))
                    }
                }
            }
        })

        recognizer.startListening(intent)
    }

    // ===================== 本地AI模型 =====================
    private fun loadModel(): Interpreter {
        val fd: AssetFileDescriptor = assets.openFd("kingassistant_model.tflite")
        val inputStream = fd.createInputStream()
        val buffer: MappedByteBuffer = inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
        return Interpreter(buffer)
    }

    private fun predictStrategy(interpreter: Interpreter, input: FloatArray): Int {
        val output = Array(1) { FloatArray(3) }
        interpreter.run(arrayOf(input), output)
        return output[0].indices.maxByOrNull { output[0][it] } ?: 0
    }

    // ===================== 数据收集与训练接口 =====================
    private fun recordPlayerAction(action: String, screen: Bitmap) {
        gameHistory.add(GameAction(System.currentTimeMillis(), action, screen))
    }

    private fun trainAIModel() {
        // TODO: 使用 gameHistory 更新 tflite 模型（占位示例）
        Toast.makeText(this, "策略模型已更新", Toast.LENGTH_SHORT).show()
    }
}

// ===================== 数据类 =====================
data class Decision(
    val suggestion: String,
    val enemies: List<Enemy>
)
data class Enemy(val name: String, val x: Int, val y: Int)
data class GameAction(val timestamp: Long, val playerAction: String, val screenState: Bitmap)
