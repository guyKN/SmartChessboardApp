package com.guykn.smartchessboard2.ui.util

open class Event {
    private var recieved = false
    fun receive():Boolean{
        if (recieved){
            return false
        }else{
            recieved = true
            return true
        }
    }

    override fun equals(other: Any?): Boolean {
        return false
    }
}