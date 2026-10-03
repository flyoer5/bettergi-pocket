package com.bettergi.pocket.input

sealed interface AutomationAction

data class ClickAction(
    val x: Int,
    val y: Int,
) : AutomationAction

data object BackAction : AutomationAction

interface AutomationController {
    fun execute(action: AutomationAction)
}

interface ActionEmitter {
    fun emit(action: AutomationAction)
}
