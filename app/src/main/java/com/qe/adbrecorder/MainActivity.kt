package com.qe.adbrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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

    private lateinit var btn_start: Button               // 로그 수집 시작 버튼
    private lateinit var btn_pause: Button               // 로그 수집 중지 버튼
    private lateinit var btn_run_time: Button            // 현재 시간 및 업타임 표시 버튼
    private lateinit var hsv_log_area: HorizontalScrollView // 로그 가로 스크롤 영역
    private lateinit var sv_log_area: ScrollView         // 로그 세로 스크롤 영역
    private lateinit var tv_run_time: TextView           // 시간 및 업타임 출력 뷰
    private lateinit var tv_log_area: TextView           // 안내 메시지 출력용 텍스트 뷰

    private var userScrolling: Boolean = false           // 사용자가 수동 스크롤 중인지 여부
    private val logBuffer = SpannableStringBuilder()     // 텍스트뷰에 표시할 메시지를 저장하는 버퍼

    // 로그 파일 생성 안내 메시지를 수신하는 BroadcastReceiver 정의
    private val fileMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("file_message") ?: return // 인텐트에서 메시지를 가져옴
            showMessage(message) // 안내 메시지 출력
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 상태 바/내비게이션 영역까지 앱이 그려질 수 있도록 설정
        setContentView(R.layout.activity_main)

        // 시스템 바에 맞게 뷰 여백 조정
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 뷰 바인딩
        btn_start = findViewById(R.id.btn_start)
        btn_pause = findViewById(R.id.btn_pause)
        btn_run_time = findViewById(R.id.btn_run_time)
        hsv_log_area = findViewById(R.id.hsv_log_area)
        sv_log_area = findViewById(R.id.sv_log_area)
        tv_run_time = findViewById(R.id.tv_run_time)
        tv_log_area = findViewById(R.id.tv_log_area)

        // 사용자가 수동 스크롤 중인지 감지하여 자동 스크롤 여부 결정
        sv_log_area.viewTreeObserver.addOnScrollChangedListener {
            val scrollY = sv_log_area.scrollY
            val maxScroll = tv_log_area.height - sv_log_area.height
            userScrolling = scrollY < maxScroll
        }

        // 버튼 클릭 이벤트 설정
        btn_start.setOnClickListener { startLogcatService() } // 로그 수집 시작
        btn_pause.setOnClickListener { stopLogcatService() } // 로그 수집 중지
        btn_run_time.setOnClickListener { runTime() } // 시간 및 업타임 표시
    }

    override fun onStart() {
        super.onStart()
        // 파일 생성 메시지를 수신하기 위한 브로드캐스트 리시버 등록
        val filter = IntentFilter("com.qe.adbrecorder.FILE_CREATED")
        LocalBroadcastManager.getInstance(this).registerReceiver(fileMessageReceiver, filter)

        // READ_LOGS 권한이 있는지 확인
        if (ContextCompat.checkSelfPermission(this, "android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
            showMessage("Missing READ_LOGS permission. Please re-grant the permission via ADB.")
        }
    }

    override fun onStop() {
        super.onStop()
        // 브로드캐스트 리시버 해제
        LocalBroadcastManager.getInstance(this).unregisterReceiver(fileMessageReceiver)
    }

    // 로그 수집 서비스 시작
    private fun startLogcatService() {
        val intent = Intent(this, LogcatService::class.java)
        ContextCompat.startForegroundService(this, intent)
        btn_start.isEnabled = false
        btn_pause.isEnabled = true
        showMessage("로그 기록이 시작되었습니다") // 안내 메시지 출력
    }

    // 로그 수집 서비스 중지
    private fun stopLogcatService() {
        val intent = Intent(this, LogcatService::class.java)
        stopService(intent)
        btn_start.isEnabled = true
        btn_pause.isEnabled = false
        showMessage("로그 기록이 중지되었습니다") // 안내 메시지 출력
    }

    // 현재 시각과 업타임 정보를 계산하여 출력
    private fun runTime() {
        val uptimeMillis = android.os.SystemClock.elapsedRealtime()
        val totalSeconds = uptimeMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val currentTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        tv_run_time.text = "Time: $currentTime  UpTime: %02d:%02d:%02d".format(hours, minutes, seconds)
    }

    // 안내 메시지를 로그 텍스트뷰(tv_log_area)에 출력하는 함수
    private fun showMessage(message: String) {
        val spannable = SpannableString(message + "\n").apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.black)), // 텍스트 색상 지정
                0,
                this.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        logBuffer.append(spannable) // 메시지 누적
        tv_log_area.text = logBuffer // 화면에 출력

        // 자동 스크롤 처리
        if (!userScrolling) {
            sv_log_area.post {
                sv_log_area.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}