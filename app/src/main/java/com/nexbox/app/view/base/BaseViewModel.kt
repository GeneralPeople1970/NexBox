package com.nexbox.app.view.base

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

open class BaseViewModel : ViewModel() {
    val loadingLiveData = MutableLiveData<Boolean>()
}
