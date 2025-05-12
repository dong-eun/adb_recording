package com.qe.adbrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    // UI 구성요소
    private lateinit var btn_start: Button               // 로그 시작 버튼
    private lateinit var btn_pause: Button               // 로그 중지 버튼
    private lateinit var btn_run_time: Button            // 현재 시간 + 업타임 버튼
    private lateinit var hsv_log_area: HorizontalScrollView // 가로 스크롤
    private lateinit var sv_log_area: ScrollView         // 세로 스크롤
    private lateinit var tv_run_time: TextView           // 시간 & 업타임 출력
    private lateinit var tv_log_area: TextView           // 로그 출력 영역

    private var userScrolling: Boolean = false           // 사용자 수동 스크롤 여부
    private val logBuffer = SpannableStringBuilder()     // 로그 누적 버퍼

    private val MAX_LOG_LINES = 5000                    // 로그 최대 유지 줄 수
    private var updateCounter = 0                        // UI 갱신 간격 카운터
    private val UPDATE_INTERVAL = 10                     // 몇 줄마다 UI 갱신할지

    // 로그 수신 브로드캐스트 리시버
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val logLine = intent?.getStringExtra("log_line") ?: return
            appendColoredLog(logLine)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 시스템 바 패딩 적용
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // UI 요소 바인딩
        btn_start = findViewById(R.id.btn_start)
        btn_pause = findViewById(R.id.btn_pause)
        btn_run_time = findViewById(R.id.btn_run_time)
        hsv_log_area = findViewById(R.id.hsv_log_area)
        sv_log_area = findViewById(R.id.sv_log_area)
        tv_run_time = findViewById(R.id.tv_run_time)
        tv_log_area = findViewById(R.id.tv_log_area)

        // 스크롤 위치에 따라 자동 스크롤 여부 판단
        sv_log_area.viewTreeObserver.addOnScrollChangedListener {
            val scrollY = sv_log_area.scrollY
            val maxScroll = tv_log_area.height - sv_log_area.height
            userScrolling = scrollY < maxScroll
        }

        // 버튼 초기 상태
        btn_start.isEnabled = true
        btn_pause.isEnabled = false

        // 버튼 클릭 리스너 등록
        btn_start.setOnClickListener { startLogcatService() }
        btn_pause.setOnClickListener { stopLogcatService() }
        btn_run_time.setOnClickListener { runTime() }
    }

    override fun onStart() {
        super.onStart()
        // 로그 수신 브로드캐스트 등록
        val filter = IntentFilter("com.qe.adbrecorder.LOG_EVENT")
        LocalBroadcastManager.getInstance(this).registerReceiver(logReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        // 로그 수신 브로드캐스트 해제
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
    }

    // 로그 서비스 시작
    private fun startLogcatService() {
        val intent = Intent(this, LogcatService::class.java)
        ContextCompat.startForegroundService(this, intent)

        btn_start.isEnabled = false
        btn_pause.isEnabled = true

        Toast.makeText(this, "로그 기록 시작됨", Toast.LENGTH_SHORT).show()
    }

    // 로그 서비스 중지
    private fun stopLogcatService() {
        val intent = Intent(this, LogcatService::class.java)
        stopService(intent)

        btn_start.isEnabled = true
        btn_pause.isEnabled = false

        Toast.makeText(this, "로그 기록 중지됨", Toast.LENGTH_SHORT).show()
    }

    // 현재 시간과 Uptime 출력
    private fun runTime() {
        val uptimeMillis = android.os.SystemClock.elapsedRealtime()
        val totalSeconds = uptimeMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val currentTime = java.text.SimpleDateFormat(
            "HH:mm:ss", java.util.Locale.getDefault()
        ).format(java.util.Date())

        tv_run_time.text = "Time: $currentTime  UpTime: %02d:%02d:%02d".format(hours, minutes, seconds)
    }

    // 로그에 색상 적용하여 출력 + 자동 스크롤 + 로그 버퍼 제한 + UI 갱신 주기 조절
    private fun appendColoredLog(line: String) {
        val colorResId = when {
            line.contains(" A ") -> R.color.log_assert
            line.contains(" E ") -> R.color.log_error
            line.contains(" W ") -> R.color.log_warning
            line.contains(" I ") -> R.color.log_info
            line.contains(" D ") -> R.color.log_debug
            line.contains(" V ") -> R.color.log_verbose
            else -> R.color.black
        }

        val color = ContextCompat.getColor(this, colorResId)

        val spannable = SpannableString(line + "\n").apply {
            setSpan(
                ForegroundColorSpan(color),
                0,
                this.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        logBuffer.append(spannable)

        // 최대 줄 수 초과 시 오래된 로그 제거
        val lines = logBuffer.split("\n")
        if (lines.size > MAX_LOG_LINES) {
            val trimmed = lines.takeLast(MAX_LOG_LINES).joinToString("\n")
            logBuffer.clear()
            logBuffer.append(trimmed).append("\n")
        }

        // 일정 횟수마다만 UI 갱신 (10줄마다)
        updateCounter++
        if (updateCounter >= UPDATE_INTERVAL) {
            updateCounter = 0
            tv_log_area.setTextKeepState(logBuffer)
            if (!userScrolling) {
                sv_log_area.post {
                    sv_log_area.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }
    }
}