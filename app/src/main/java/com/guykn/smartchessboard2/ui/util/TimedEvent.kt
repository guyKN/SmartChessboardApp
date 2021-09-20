package com.guykn.smartchessboard2.ui.util

open class TimedEvent(private val duration: Long = 5*1000): Event() {
    private val expireTime = System.currentTimeMillis() + duration
    override fun receive(): Boolean {
        // a duration of -1 indicates infinite duration
        if (duration == -1L || System.currentTimeMillis() < expireTime){
            return super.receive()
        }else{
            return false
        }
    }
}