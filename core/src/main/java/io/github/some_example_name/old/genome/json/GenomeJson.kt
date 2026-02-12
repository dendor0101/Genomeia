package io.github.some_example_name.old.genome.json

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonWriter
import io.github.some_example_name.old.genome.json.read.CreatureJsonRead
import io.github.some_example_name.old.genome.json.write.CreatureJsonWrite
import java.io.File

class GenomeJsonReader() {

    private val json = Json()

    val saveDir: FileHandle = when (Gdx.app.type) {
        Application.ApplicationType.Desktop -> {
            val jarFile = File(GenomeJsonReader::class.java.protectionDomain.codeSource.location.toURI())
            Gdx.files.absolute(jarFile.parentFile.absolutePath)
        }
        Application.ApplicationType.Android -> {
            Gdx.files.local("")  // Локальное приватное хранилище приложения на Android
        }
        else -> {
            Gdx.files.local("")  // Для других платформ, например, iOS или Web
        }
    }

    init {
        //TODO перед чтением всех json-ов проверить наличие .bin, перевести их в json-ы и удалить
        json.setOutputType(JsonWriter.OutputType.json)
        json.setUsePrototypes(false)
    }

    fun readAllGenomesFromAssetsFolder(relativeFolderName: String): List<CreatureJsonRead> {
        val folder: FileHandle = Gdx.files.internal(relativeFolderName.trimEnd('/')) // Удалить trailing slash, если есть
        println("Looking for folder: ${folder.path()}")  // Покажет, где ищет
        if (!folder.exists()) {
            println("Folder not found or not a directory: ${folder.path()}")
            return emptyList()
        }
        val genomes = mutableListOf<CreatureJsonRead>()
        // Пытаемся использовать list(), что работает на Android и desktop в dev-режиме
        val files = folder.list(".json") // Альтернатива: list() с фильтром по extension
        if (files.isNotEmpty()) {
            files.filter { !it.isDirectory && it.extension() == "json" }
                .forEach { file ->
                    try {
                        val jsonString = file.readString()
                        val creatureJson = json.fromJson(CreatureJsonRead::class.java, jsonString)
                        println("Deserialized from ${file.name()}: $creatureJson")
                        genomes.add(creatureJson)
                    } catch (e: Exception) {
                        println("Failed to read ${file.name()}: ${e.message}")
                    }
                }
        } else {
            // Workaround для JAR/desktop: если list() пустой, используем assets.txt
            println("list() returned empty, falling back to assets.txt")
            val assetsList: FileHandle? = Gdx.files.internal("assets.txt")
            if (assetsList != null && assetsList.exists()) {
                val allPaths = assetsList.readString().split("\n")
                val targetFolder = if (relativeFolderName.isEmpty()) "" else "${relativeFolderName.trimEnd('/')}/"
                allPaths.filter { it.startsWith(targetFolder) && !it.substring(targetFolder.length).contains("/") && it.endsWith(".json") }
                    .forEach { path ->
                        val file = Gdx.files.internal(path)
                        if (file.exists()) {
                            try {
                                val jsonString = file.readString()
                                val creatureJson = json.fromJson(CreatureJsonRead::class.java, jsonString)
                                println("Deserialized from ${file.name()}: $creatureJson")
                                genomes.add(creatureJson)
                            } catch (e: Exception) {
                                println("Failed to read ${file.name()}: ${e.message}")
                            }
                        }
                    }
            } else {
                println("assets.txt not found for workaround")
            }
        }
        return genomes
    }

    fun readGenomeFromFile(relativeFileName: String): CreatureJsonRead? {
        val fileHandle: FileHandle = saveDir.child(relativeFileName)
        if (!fileHandle.exists()) return null
        return json.fromJson(CreatureJsonRead::class.java, fileHandle.readString())
    }

    fun saveGenomeToFile(creature: CreatureJsonWrite, relativeFileName: String) {
        val fileHandle: FileHandle = saveDir.child(relativeFileName)
        fileHandle.parent().mkdirs() // Создать родительские директории, если нужно
        val jsonString = json.prettyPrint(creature)
        fileHandle.writeString(jsonString, false)
        println("Genome written to ${fileHandle.path()}")
    }

    fun readAllGenomesFromFolder(relativeFolderName: String): List<CreatureJsonRead> {
        val folderHandle: FileHandle = saveDir.child(relativeFolderName)
        if (!folderHandle.exists() || !folderHandle.isDirectory) {
            println("Folder not found: ${folderHandle.path()}")
            return emptyList()
        }

        val genomes = mutableListOf<CreatureJsonRead>()

        folderHandle.list()
            .filter { !it.isDirectory && it.extension().equals("json", ignoreCase = true) }
            .forEach { fileHandle ->
                try {
                    val jsonString = fileHandle.readString()
                    val creatureJson = json.fromJson(CreatureJsonRead::class.java, jsonString)
                    println("Deserialized from ${fileHandle.name()}: $creatureJson")
                    genomes.add(creatureJson)
                } catch (e: Exception) {
                    println("Failed to read ${fileHandle.name()}: ${e.message}")
                }
            }

        return genomes
    }


    fun copyClipboardJson(creature: CreatureJsonWrite) {
        val jsonString = json.prettyPrint(creature)
        Gdx.app.clipboard.contents = jsonString
    }

    // New methods below

    fun getGenomeFileNamesFromAssetsFolder(relativeFolderName: String): List<String> {
        val folder: FileHandle = Gdx.files.internal(relativeFolderName.trimEnd('/'))
        println("Looking for folder: ${folder.path()}")
        if (!folder.exists()) {
            println("Folder not found or not a directory: ${folder.path()}")
            return emptyList()
        }
        val names = mutableListOf<String>()
        val files = folder.list(".json")
        if (files.isNotEmpty()) {
            files.filter { !it.isDirectory && it.extension() == "json" }
                .forEach { file ->
                    names.add(file.nameWithoutExtension())
                }
        } else {
            println("list() returned empty, falling back to assets.txt")
            val assetsList: FileHandle? = Gdx.files.internal("assets.txt")
            if (assetsList != null && assetsList.exists()) {
                val allPaths = assetsList.readString().split("\n")
                val targetFolder = if (relativeFolderName.isEmpty()) "" else "${relativeFolderName.trimEnd('/')}/"
                allPaths.filter { it.startsWith(targetFolder) && !it.substring(targetFolder.length).contains("/") && it.endsWith(".json") }
                    .forEach { path ->
                        val fileName = path.substringAfterLast('/').removeSuffix(".json")
                        names.add(fileName)
                    }
            } else {
                println("assets.txt not found for workaround")
            }
        }
        return names
    }

    fun getGenomeFileNamesFromFolder(relativeFolderName: String): List<String> {
        val folderHandle: FileHandle = saveDir.child(relativeFolderName)
        if (!folderHandle.exists() || !folderHandle.isDirectory) {
            println("Folder not found: ${folderHandle.path()}")
            return emptyList()
        }
        return folderHandle.list()
            .filter { !it.isDirectory && it.extension().equals("json", ignoreCase = true) }
            .map { it.nameWithoutExtension() }
    }

    fun readGenomeFromAssetsFolder(relativeFolderName: String, fileName: String): CreatureJsonRead? {
        val relativePath = if (relativeFolderName.isEmpty()) "$fileName.json" else "${relativeFolderName.trimEnd('/')}/$fileName.json"
        val file: FileHandle = Gdx.files.internal(relativePath)
        if (!file.exists()) {
            println("File not found: ${file.path()}")
            return null
        }
        try {
            val jsonString = file.readString()
            return json.fromJson(CreatureJsonRead::class.java, jsonString)
        } catch (e: Exception) {
            println("Failed to read ${file.name()}: ${e.message}")
            return null
        }
    }

    fun readGenomeFromFolder(relativeFolderName: String, fileName: String, isNecessaryDotJson: Boolean = true): CreatureJsonRead? {
        val folderHandle: FileHandle = saveDir.child(relativeFolderName)
        if (!folderHandle.exists() || !folderHandle.isDirectory) {
            println("Folder not found: ${folderHandle.path()}")
            return null
        }
        val fileHandle: FileHandle = folderHandle.child("$fileName${if (isNecessaryDotJson) ".json" else ""}")
        if (!fileHandle.exists()) {
            println("File not found: ${fileHandle.path()}")
            return null
        }
        try {
            val jsonString = fileHandle.readString()
            return json.fromJson(CreatureJsonRead::class.java, jsonString)
        } catch (e: Exception) {
            println("Failed to read ${fileHandle.name()}: ${e.message}")
            return null
        }
    }
}
