package app.ui


import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import rehabcore.domain.ActionType


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val btnSquat = Button(this).apply { text = "深蹲" }
        val btnCalf = Button(this).apply { text = "提踵" }
        btnSquat.setOnClickListener { start(ActionType.SQUAT) }
        btnCalf.setOnClickListener { start(ActionType.CALF) }
        root.addView(btnSquat)
        root.addView(btnCalf)
        setContentView(root)
    }

    private fun start(action: ActionType) {
        val it = Intent(this, DetectionActivity::class.java)
            .putExtra(DetectionActivity.EXTRA_ACTION, action.name)
        startActivity(it)
    }
}