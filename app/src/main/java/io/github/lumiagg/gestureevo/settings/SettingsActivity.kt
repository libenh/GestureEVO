package io.github.lumiagg.gestureevo.settings

import android.content.Intent
import android.os.Bundle
import android.transition.Fade
import android.util.Log
import android.view.View
import androidx.core.app.ActivityOptionsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivitySettingsBinding
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.settings.ui.showAboutBottomSheet
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.root.RootShell
import io.github.lumiagg.gestureevo.utils.PreferenceDataStoreAdapter
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class SettingsActivity : AppCompatActivity() {
    lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()
        applySystemBarInsets()
        setupSearchTransition()

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        binding.settingsSearchEntryCard.setOnClickListener {
            openSettingsSearch()
        }

    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = DynamicColorUtils.surfaceContainerColor
    }

    private fun applySystemBarInsets() {
        val initialTop = binding.appBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                initialTop + systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.appBar)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (supportFragmentManager.findFragmentById(R.id.settings) as? SettingsFragment)
            ?.handlePendingNavigation()
    }

    private fun setupSearchTransition() {
        window.sharedElementExitTransition = SettingsSearchNavigator.buildSharedElementTransition(this)
        window.sharedElementReenterTransition = SettingsSearchNavigator.buildSharedElementTransition(this)
        window.exitTransition = Fade().apply { duration = 140L }
        window.reenterTransition = Fade().apply { duration = 140L }
    }

    private fun openSettingsSearch() {
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            this,
            binding.settingsSearchEntryCard,
            getString(R.string.settings_search_box_transition_name)
        )
        startActivity(Intent(this, SettingsSearchActivity::class.java), options.toBundle())
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var preferenceService: Preference? = null

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            setDivider(null)
            listView.clipToPadding = false
            val initialBottom = listView.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(listView) { list, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight,
                    initialBottom + bottom + (16 * resources.displayMetrics.density).toInt())
                insets
            }
            ViewCompat.requestApplyInsets(listView)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore =
                PreferenceDataStoreAdapter(requireContext().settingsStore, lifecycleScope)
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            findPreference<Preference>("preferenceDynamicShortcuts")?.setOnPreferenceClickListener {
                startActivity(Intent(requireActivity(), DynamicShortcutSettingsActivity::class.java))
                true
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    refreshConfigurationSummaries()
                }
            }

            findPreference<Preference>("preferenceSettingsGuide")?.setOnPreferenceClickListener {
                startActivity(Intent(requireActivity(), SettingsGuideActivity::class.java))
                true
            }

            findPreference<Preference>("preferenceConfigTrigger")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            FreeTriggerSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceActionView")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            ActionViewSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceAppearance")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            AppearanceSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceSearch")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            SearchEngineSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceFreeform")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            FreeFormSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceAbout")?.apply {
                setOnPreferenceClickListener {
                    showAboutBottomSheet(requireActivity() as AppCompatActivity)
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceData")?.apply {
                setOnPreferenceClickListener {
                    startActivity(Intent(requireActivity(), DataBackActivity::class.java))
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceExternalAction")?.apply {
                setOnPreferenceClickListener {
                    startActivity(Intent(requireActivity(), ExternalActionSettingsActivity::class.java))
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceLink")?.apply {
                setOnPreferenceClickListener {
                    startActivity(Intent(requireActivity(), LinkRuleSettingsActivity::class.java))
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceInspire")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            InspireSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceBackgroundStability")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            BackgroundStabilityActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            preferenceService = findPreference<Preference>("preferenceService")?.apply {
                updateRootStatus(this)
                setOnPreferenceClickListener {
                    // Calling su triggers the installed root manager's normal authorization prompt.
                    updateRootStatus(this)
                    true
                }
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceService?.let { updateRootStatus(it) }
            handlePendingNavigation()
        }

        fun handlePendingNavigation() {
            val targetPreferenceKey = activity?.intent
                ?.getStringExtra(SettingsSearchNavigator.EXTRA_TARGET_PREFERENCE_KEY)
                ?: return
            listView?.post {
                scrollToPreference(targetPreferenceKey)
                activity?.intent?.removeExtra(SettingsSearchNavigator.EXTRA_TARGET_PREFERENCE_KEY)
            }
        }

        private suspend fun refreshConfigurationSummaries() {
            val context = requireContext().applicationContext
            try {
                val triggers = FreeTriggerDataManager.readTriggers(context)
                val groups = GestureActionDataManager.readActionListTags(context)
                findPreference<Preference>("preferenceConfigTrigger")?.summary = getString(
                    R.string.settings_trigger_count, triggers.size, triggers.count { it.enable }
                )
                findPreference<Preference>("preferenceActionView")?.summary = getString(
                    R.string.settings_wheel_count, groups.size,
                    groups.firstOrNull { it.tag == ActionConfig.DEFAULT_TAG }?.name.orEmpty()
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("SettingsActivity", "Unable to read configuration summaries", failure)
                listOf("preferenceConfigTrigger", "preferenceActionView").forEach {
                    findPreference<Preference>(it)?.setSummary(R.string.settings_summary_unavailable)
                }
            }
        }

        private fun updateRootStatus(preference: Preference) {
            lifecycleScope.launch {
                val granted = RootShell.isAvailable()
                preference.title = getString(
                    if (granted) R.string.settings_root_running else R.string.settings_root_off
                )
                preference.summary = getString(R.string.settings_root_summary)
            }
        }
    }
}
