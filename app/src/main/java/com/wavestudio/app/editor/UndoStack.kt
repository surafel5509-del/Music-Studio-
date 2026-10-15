package com.wavestudio.app.editor

import java.util.Stack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generic undo/redo stack for non-destructive edits. Each operation is reversible.
 */
@Singleton
class UndoStack @Inject constructor() {

    interface UndoableAction {
        val name: String
        fun undo()
        fun redo()
    }

    private val undo = Stack<UndoableAction>()
    private val redo = Stack<UndoableAction>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun push(action: UndoableAction) {
        action.redo()
        undo.push(action)
        redo.clear()
    }

    fun undo() {
        if (!canUndo) return
        val a = undo.pop()
        a.undo()
        redo.push(a)
    }

    fun redo() {
        if (!canRedo) return
        val a = redo.pop()
        a.redo()
        undo.push(a)
    }

    fun clear() { undo.clear(); redo.clear() }

    fun peekUndoName(): String? = undo.lastOrNull()?.name
    fun peekRedoName(): String? = redo.lastOrNull()?.name
}
