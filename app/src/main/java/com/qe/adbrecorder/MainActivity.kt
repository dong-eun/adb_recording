package com.qe.adbrecorder

import android.content.Context
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btn_start : Button
    private lateinit var btn_pause : Button
    private lateinit var btn_run_time : Button
    private lateinit var hsv_log_area : HorizontalScrollView
    private lateinit var sv_log_area : ScrollView
    private lateinit var tv_run_time : TextView
    private lateinit var tv_log_area: TextView

    private var isRunning : Boolean = false
    private var userScrolling : Boolean = false
    private val logBuffer = StringBuilder()

    private var logcatThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btn_start = findViewById<Button>(R.id.btn_start)
        btn_pause = findViewById<Button>(R.id.btn_pause)
        btn_run_time = findViewById<Button>(R.id.btn_run_time)
        tv_run_time = findViewById<TextView>(R.id.tv_run_time)
        hsv_log_area = findViewById<HorizontalScrollView>(R.id.hsv_log_area)
        sv_log_area = findViewById<ScrollView>(R.id.sv_log_area)
        tv_log_area = findViewById<TextView>(R.id.tv_log_area)

        sv_log_area.viewTreeObserver.addOnScrollChangedListener {
            val scrollY = sv_log_area.scrollY
            val maxScroll = tv_log_area.height - sv_log_area.height

            // 사용자가 스크롤을 위로 올리면 자동 스크롤 막기
            userScrolling = scrollY < maxScroll
        }

        // Button 초기 상태 설정
        btn_start.isEnabled = true
        btn_pause.isEnabled = false
        btn_run_time.isEnabled = true

        btn_start.setOnClickListener {
            startADB()
        }

        btn_pause.setOnClickListener {
            pauseADB()
        }

        btn_run_time.setOnClickListener {
            runTime()
        }
    }

    fun getLogColor(context: Context, line: String): Int {
        return when {
            line.contains(" V ") || line.startsWith("V/") -> ContextCompat.getColor(context, R.color.log_verbose)
            line.contains(" D ") || line.startsWith("D/") -> ContextCompat.getColor(context, R.color.log_debug)
            line.contains(" I ") || line.startsWith("I/") -> ContextCompat.getColor(context, R.color.log_info)
            line.contains(" W ") || line.startsWith("W/") -> ContextCompat.getColor(context, R.color.log_warning)
            line.contains(" E ") || line.startsWith("E/") -> ContextCompat.getColor(context, R.color.log_error)
            line.contains(" A ") || line.startsWith("A/") -> ContextCompat.getColor(context, R.color.log_assert)

            else -> ContextCompat.getColor(context, R.color.black)
        }
    }

    private fun startADB() {
        // 버튼 비활성화
        btn_start.isEnabled = false

        // 상태 플래그 변경
        isRunning = true

        // 필요하면 stop 버튼 활성화도 여기에 추가 가능
        btn_pause.isEnabled = true

        // logcat 로그 읽는 스레드 시작
        logcatThread = Thread {
            try {
                val process = Runtime.getRuntime().exec("logcat -v time")
                val reader = process.inputStream.bufferedReader()

                var line: String?
                while (reader.readLine().also { line = it } != null && isRunning) {
                    val logLine = line ?: continue
                    runOnUiThread {
                        val spannable = SpannableStringBuilder(logLine + "\n")
                        val color = getLogColor(this, logLine)

                        spannable.setSpan(
                            ForegroundColorSpan(color),
                            0,
                            spannable.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )

                        tv_log_area.append(spannable)

                        if (!userScrolling) {
                            sv_log_area.post {
                                sv_log_area.fullScroll(ScrollView.FOCUS_DOWN)
                            }
                        }

                        if (tv_log_area.lineCount > 1000) {
                            val currentText = tv_log_area.text.toString()
                            val halfIndex = currentText.length / 2
                            val trimmedText = currentText.substring(halfIndex)
                            tv_log_area.text = "--- 로그가 초기화되었습니다 ---\n" + trimmedText
                        }
                    }
                }

                reader.close()
                process.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        logcatThread?.start()
    }

    private fun pauseADB() {
        // 상태 플래그 변경
        isRunning = false

        // 로그 수집 스레드 정리
        logcatThread?.interrupt()
        logcatThread = null

        // 버튼 상태 변경
        btn_start.isEnabled = true
        btn_pause.isEnabled = false
    }

    private fun runTime() {
        // 부팅 이후 경과된 시간(ms 단위) 가져오기 (기기가 켜진 후부터의 시간)
        val uptimeMillis = android.os.SystemClock.elapsedRealtime()

        // 총 시간(ms)을 초 단위로 변환
        val totalSeconds = uptimeMillis / 1000

        // 시, 분, 초로 나누기
        val hours = totalSeconds / 3600 // 전체 시간 중 시(hour) 계산
        val minutes = (totalSeconds % 3600) / 60 // 남은 시간 중 분(minute) 계산
        val seconds = totalSeconds % 60 // 남은 시간 중 초(second) 계산

        // 현재 시각 가져오기 (시:분:초 형태)
        val currentTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

        // TextView에 현재 시간과 업타임을 포맷에 맞게 출력
        tv_run_time.text = "Time: $currentTime  UpTime: %02d:%02d:%02d".format(hours, minutes, seconds)
    }
}