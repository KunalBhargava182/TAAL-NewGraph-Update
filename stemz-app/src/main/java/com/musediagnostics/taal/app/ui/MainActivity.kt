package com.musediagnostics.taal.app.ui

import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import android.view.Gravity
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.musediagnostics.taal.app.R
import com.musediagnostics.taal.app.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("taal_prefs", MODE_PRIVATE)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController

        // Lock drawer on splash/auth screens, unlock on main screens
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.splashFragment, R.id.signInFragment, R.id.loginFragment,
                R.id.otpFragment, R.id.setPinFragment, R.id.pinConfirmedFragment,
                R.id.pinLoginFragment, R.id.fingerprintSetupFragment,
                R.id.fingerprintConfirmFragment, R.id.signUpFragment,
                R.id.recordingFragment -> {
                    binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                }
                else -> {
                    binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                }
            }
        }

        setupNavigationDrawer()
    }

    /** Refresh drawer header name and photo from SharedPreferences */
    private fun refreshDrawerHeader() {
        try {
            val headerView = binding.navView.getHeaderView(0) ?: return
            val profileNameView = headerView.findViewById<TextView>(R.id.profileName)
            val profileImageView = headerView.findViewById<ImageView>(R.id.profileImage)

            // Update name
            val savedName = prefs.getString("profile_name", "Doctor")
            profileNameView?.text = if (savedName.isNullOrBlank()) "Doctor" else savedName

            // Update photo
            val photoPath = prefs.getString("profile_photo_path", null)
            if (photoPath != null) {
                val file = File(photoPath)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null && profileImageView != null) {
                        profileImageView.setImageBitmap(bitmap)
                        profileImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        profileImageView.setPadding(0, 0, 0, 0)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun setupNavigationDrawer() {
        // Setup header close button
        val headerView = binding.navView.getHeaderView(0)
        val closeButton = headerView.findViewById<ImageButton>(R.id.drawerCloseButton)
        closeButton.setOnClickListener {
            binding.drawerLayout.closeDrawers()
        }

        // Initial header refresh
        refreshDrawerHeader()

        // Refresh header every time drawer opens (picks up profile changes)
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: android.view.View, slideOffset: Float) {}
            override fun onDrawerOpened(drawerView: android.view.View) {
                refreshDrawerHeader()
            }
            override fun onDrawerClosed(drawerView: android.view.View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })

        // Add sign out footer at the BOTTOM of the NavigationView using FrameLayout gravity
        val footerView = LayoutInflater.from(this)
            .inflate(R.layout.nav_footer, binding.navView, false)
        val footerParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM
        )
        binding.navView.addView(footerView, footerParams)
        val signOutButton = footerView.findViewById<MaterialButton>(R.id.signOutButton)
        signOutButton.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            prefs.edit().putBoolean("is_logged_in", false).apply()
            navController.navigate(R.id.loginFragment)
        }

        // Menu item navigation
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayout.closeDrawers()
            when (menuItem.itemId) {
                R.id.nav_profile -> {
                    navController.navigate(R.id.profileFragment)
                    true
                }
                R.id.nav_library -> {
                    navController.navigate(R.id.recordingLibraryFragment)
                    true
                }
                R.id.nav_recording -> {
                    navController.popBackStack(R.id.recordingFragment, false)
                    true
                }
                R.id.nav_set_pin -> {
                    navController.navigate(R.id.changePinFragment)
                    true
                }
                R.id.nav_about -> {
                    navController.navigate(R.id.aboutUsFragment)
                    true
                }
                R.id.nav_faq -> {
                    navController.navigate(R.id.faqFragment)
                    true
                }
                R.id.nav_privacy -> {
                    navController.navigate(R.id.privacyPolicyFragment)
                    true
                }
                R.id.nav_subscription -> {
                    navController.navigate(R.id.subscriptionFragment)
                    true
                }
                R.id.nav_user_manual -> {
                    navController.navigate(R.id.userManualFragment)
                    true
                }
                else -> false
            }
        }
    }

    fun openDrawer() {
        binding.drawerLayout.openDrawer(binding.navView)
    }

    fun closeDrawer() {
        binding.drawerLayout.closeDrawers()
    }

    fun getDrawerLayout(): DrawerLayout = binding.drawerLayout
}
