package io.github.khorkovenko.easysnippets

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.nio.file.Files
import java.nio.file.Path

/**
 * JSON import and export:
 * ```
 * { "version": 1, "snippets": [
 *     { "description": "List files", "command": "ls -la" },
 *     { "folder": "Git", "items": [ { "description": "Status", "command": "git status", "pasteOnly": false, "favorite": true } ] } ] }
 * ```
 */
object SnippetTransfer {

    fun toJson(items: List<SnippetItem>): String {
        val root = JsonObject()
        root.addProperty("version", 1)
        root.add("snippets", toArray(items))
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
    }

    private fun toArray(items: List<SnippetItem>): JsonArray {
        val array = JsonArray()
        for (item in items) {
            val json = JsonObject()
            if (item.folder) {
                json.addProperty("folder", item.name)
                json.add("items", toArray(item.children))
            } else {
                json.addProperty("description", item.name)
                json.addProperty("command", item.command)
                if (item.pasteOnly) json.addProperty("pasteOnly", true)
            }
            if (item.favorite) json.addProperty("favorite", true)
            array.add(json)
        }
        return array
    }

    /** Accepts the exported object or a bare array of entries. Throws [IllegalArgumentException] on anything else. */
    fun fromJson(text: String): List<SnippetItem> {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("The file is not valid JSON: ${e.message}")
        }
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.get("snippets")?.isJsonArray == true -> root.asJsonObject.getAsJsonArray("snippets")
            else -> throw IllegalArgumentException("Expected a \"snippets\" array")
        }
        return fromArray(array)
    }

    private fun fromArray(array: JsonArray): List<SnippetItem> = array.map { element ->
        if (!element.isJsonObject) throw IllegalArgumentException("Every entry must be an object")
        val json = element.asJsonObject
        val folder = json.string("folder")
        if (folder != null) {
            val children = json.get("items")?.takeIf { it.isJsonArray }?.asJsonArray
            newFolder(folder, children?.let { fromArray(it) } ?: emptyList()).also { it.favorite = json.flag("favorite") }
        } else {
            val command = json.string("command") ?: throw IllegalArgumentException("An entry has neither \"command\" nor \"folder\"")
            val pasteOnly = json.flag("pasteOnly")
            newSnippet(json.string("description") ?: command.lines().first(), command, pasteOnly).also { it.favorite = json.flag("favorite") }
        }
    }

    private fun JsonObject.flag(name: String): Boolean = get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { e: JsonElement -> e.isJsonPrimitive }?.asString

    /** Exports [selection] when it is not empty, otherwise everything. */
    fun export(project: Project, selection: List<SnippetItem>) {
        val items = selection.ifEmpty { SnippetStore.getInstance().items }
        val descriptor = FileSaverDescriptor("Export Easy Snippets",
            if (selection.isEmpty()) "Save all commands as JSON" else "Save the selected commands as JSON", "json")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(null as Path?, "easy-snippets.json") ?: return
        try {
            Files.writeString(target.file.toPath(), toJson(items))
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Cannot write ${target.file}: ${e.message}", "Export Easy Snippets")
        }
    }

    /** Returns the imported top-level items, or null when nothing was imported. */
    fun import(project: Project): List<SnippetItem>? {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json").withTitle("Import Easy Snippets")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return null
        val imported = try {
            // Read from disk, not the VFS cache: the file is often edited outside the IDE just before importing
            fromJson(Files.readString(file.toNioPath()))
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Cannot import ${file.name}: ${e.message}", "Import Easy Snippets")
            return null
        }
        if (imported.isEmpty()) {
            Messages.showInfoMessage(project, "${file.name} contains no commands.", "Import Easy Snippets")
            return null
        }
        val store = SnippetStore.getInstance()
        if (store.items.isNotEmpty()) {
            val count = imported.sumOf { it.snippets().size }
            val choice = Messages.showYesNoCancelDialog(project,
                "Import $count command(s) from ${file.name}.\nAdd them to the existing commands, or replace everything?",
                "Import Easy Snippets", "Add", "Replace All", "Cancel", Messages.getQuestionIcon())
            when (choice) {
                Messages.YES -> {}
                Messages.NO -> store.items.clear()
                else -> return null
            }
        }
        store.items.addAll(imported)
        store.changed()
        return imported
    }
}
