package app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.google.mediapipe.examples.poselandmarker.databinding.ActivityMainBinding
import rehabcore.domain.ActionType

/**
 * 改進版本：
 * 1. 使用 ViewBinding 取代動態 LinearLayout，提升維護性。
 * 2. 按鈕文字改由 strings.xml 管理，支援多語系。
 * 3. 程式結構更清晰，具備可讀性與擴充性。
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 設定按鈕點擊事件
        with(binding) {
            buttonSquat.setOnClickListener { start(ActionType.SQUAT) }
            buttonCalf.setOnClickListener { start(ActionType.CALF) }
            buttonRehabCalf.setOnClickListener { start(ActionType.REHAB_CALF) }
        }
    }

    private fun start(action: ActionType) {
        val intent = Intent(this, DetectionActivity::class.java)
            .putExtra(DetectionActivity.EXTRA_ACTION, action.name)
        startActivity(intent)
    }
}