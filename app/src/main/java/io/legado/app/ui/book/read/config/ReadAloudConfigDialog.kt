package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.edge.EdgeTtsEngine
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = LinearLayout(requireContext())
        view.setBackgroundColor(requireContext().backgroundColor)
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadAloudPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readAloudPreferTag)
            .commit()
    }

    class ReadAloudPreferenceFragment : PreferenceFragment(),
        SpeakEngineDialog.CallBack,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val speakEngineSummary: String
            get() {
                val ttsEngine = ReadAloud.ttsEngine
                    ?: return getString(R.string.system_tts)
                if (StringUtils.isNumeric(ttsEngine)) {
                    return appDb.httpTTSDao.getName(ttsEngine.toLong())
                        ?: getString(R.string.system_tts)
                }
                return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
                    ?: getString(R.string.system_tts)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_aloud)
            upSpeakEngineSummary()
            upEdgeVoiceSummary()
            findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                it.isEnabled = AppConfig.ignoreAudioFocus
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                PreferKey.ttsEngine -> showDialogFragment(SpeakEngineDialog())
                "sysTtsConfig" -> IntentHelp.openTTSSetting()
                "edgeTtsVoicePick" -> showDialogFragment(EdgeVoiceDialog())
                "edgeTtsSsmlEdit" -> showEdgeSsmlEditor()
            }
            return super.onPreferenceTreeClick(preference)
        }

        /**
         * 自定义 SSML 编辑：留空恢复默认模板
         */
        private fun showEdgeSsmlEditor() {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.edge_tts_custom_ssml_hint)
                editView.setText(AppConfig.edgeTtsCustomSsml)
                editView.minLines = 6
            }
            alert(R.string.edge_tts_custom_ssml) {
                customView { alertBinding.root }
                positiveButton(R.string.ok) {
                    AppConfig.edgeTtsCustomSsml = alertBinding.editView.text?.toString().orEmpty()
                    upEdgeVoiceSummary()
                }
                negativeButton(R.string.cancel)
                neutralButton(R.string.edge_tts_ssml_reset) {
                    AppConfig.edgeTtsCustomSsml = ""
                    upEdgeVoiceSummary()
                }
            }
        }

        private fun upEdgeVoiceSummary() {
            val voice = EdgeTtsEngine.voice
            val custom = AppConfig.edgeTtsCustomSsml
            findPreference<Preference>("edgeTtsVoicePick")?.summary = if (custom.isNotBlank()) {
                getString(R.string.edge_tts_voice_with_ssml, voice)
            } else {
                voice
            }
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.ignoreAudioFocus -> {
                    findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                        it.isEnabled = AppConfig.ignoreAudioFocus
                    }
                }
            }
        }

        private fun upPreferenceSummary(preference: Preference?, value: String) {
            when (preference) {
                is ListPreference -> {
                    val index = preference.findIndexOfValue(value)
                    preference.summary = if (index >= 0) preference.entries[index] else null
                }

                else -> {
                    preference?.summary = value
                }
            }
        }

        override fun upSpeakEngineSummary() {
            upPreferenceSummary(
                findPreference(PreferKey.ttsEngine),
                speakEngineSummary
            )
        }
    }
}