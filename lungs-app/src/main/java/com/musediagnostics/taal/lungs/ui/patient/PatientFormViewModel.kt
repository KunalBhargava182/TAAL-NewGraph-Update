package com.musediagnostics.taal.lungs.ui.patient

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class PatientFormViewModel : ViewModel() {

    private val _height = MutableLiveData<Float?>(null)
    private val _weight = MutableLiveData<Float?>(null)

    val bmi: LiveData<Float?> = MediatorLiveData<Float?>().apply {
        fun compute() {
            val h = _height.value
            val w = _weight.value
            value = if (h != null && h > 0f && w != null && w > 0f) {
                val hMeters = h / 100f
                w / (hMeters * hMeters)
            } else null
        }
        addSource(_height) { compute() }
        addSource(_weight) { compute() }
    }

    fun setHeight(cm: Float?) { _height.value = cm }
    fun setWeight(kg: Float?) { _weight.value = kg }
}
