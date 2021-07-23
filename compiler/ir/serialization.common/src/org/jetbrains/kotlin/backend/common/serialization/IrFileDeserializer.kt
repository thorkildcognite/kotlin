/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.library.IrLibrary
import org.jetbrains.kotlin.library.encodings.WobblyTF8
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.protobuf.CodedInputStream
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite
import org.jetbrains.kotlin.backend.common.serialization.proto.IrConstructorCall as ProtoConstructorCall
import org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration as ProtoDeclaration
import org.jetbrains.kotlin.backend.common.serialization.proto.IrFile as ProtoFile

class IrFileDeserializer(
    val file: IrFile,
    private val fileReader: IrLibraryFile,
    fileProto: ProtoFile,
    val symbolDeserializer: IrSymbolDeserializer,
    val declarationDeserializer: IrDeclarationDeserializer,
) {
    val reversedSignatureIndex = fileProto.declarationIdList.associateBy { symbolDeserializer.deserializeIdSignature(it) }

    private var annotations: List<ProtoConstructorCall>? = fileProto.annotationList

    fun deserializeDeclaration(idSig: IdSignature): IrDeclaration {
        return declarationDeserializer.deserializeDeclaration(loadTopLevelDeclarationProto(idSig)).also {
            file.declarations += it
        }
    }

    private fun readDeclaration(index: Int): CodedInputStream =
        fileReader.irDeclaration(index).codedInputStream

    private fun loadTopLevelDeclarationProto(idSig: IdSignature): ProtoDeclaration {
        val idSigIndex = reversedSignatureIndex[idSig] ?: error("Not found Idx for $idSig")
        return ProtoDeclaration.parseFrom(readDeclaration(idSigIndex), ExtensionRegistryLite.newInstance())
    }

    fun deserializeFileImplicitDataIfFirstUse() {
        annotations?.let {
            file.annotations += declarationDeserializer.deserializeAnnotations(it)
            annotations = null
        }
    }
}

class FileDeserializationState(
    val linker: KotlinIrLinker,
    file: IrFile,
    fileReader: IrLibraryFile,
    fileProto: ProtoFile,
    deserializeBodies: Boolean,
    allowErrorNodes: Boolean,
    deserializeInlineFunctions: Boolean,
    moduleDeserializer: IrModuleDeserializer,
    useGlobalSignatures: Boolean
) {

    val symbolDeserializer =
        IrSymbolDeserializer(
            linker.symbolTable, fileReader, file.symbol,
            fileProto.actualList,
            ::addIdSignature,
            linker::handleExpectActualMapping,
            useGlobalSignatures = useGlobalSignatures,
        ) { idSig, symbolKind ->

            val topLevelSig = idSig.topLevelSignature()
            val actualModuleDeserializer = moduleDeserializer.findModuleDeserializerForTopLevelId(topLevelSig)
                ?: run {
                    // The symbol might be gone in newer version of dependency KLIB. Then the KLIB that was compiled against
                    // the older version of dependency KLIB will still have a reference to non-existing symbol. And the linker will have to
                    // handle such situation appropriately. See KT-41378.
                    linker.handleSignatureIdNotFoundInModuleWithDependencies(idSig, moduleDeserializer)
                }

            actualModuleDeserializer.deserializeIrSymbol(idSig, symbolKind)
        }

    private val declarationDeserializer = IrDeclarationDeserializer(
        linker.builtIns,
        linker.symbolTable,
        linker.symbolTable.irFactory,
        fileReader,
        file,
        allowErrorNodes,
        deserializeInlineFunctions,
        deserializeBodies,
        symbolDeserializer,
        linker.fakeOverrideBuilder.platformSpecificClassFilter,
        linker.fakeOverrideBuilder,
        compatibilityMode = moduleDeserializer.compatibilityMode
    )

    val fileDeserializer = IrFileDeserializer(file, fileReader, fileProto, symbolDeserializer, declarationDeserializer)

    private val reachableTopLevels = LinkedHashSet<IdSignature>()

    init {
        // Explicitly exported declarations (e.g. top-level initializers) must be deserialized before all other declarations.
        // Thus we schedule their deserialization in deserializer's constructor.
        fileProto.explicitlyExportedToCompilerList.forEach {
            val symbolData = symbolDeserializer.parseSymbolData(it)
            val sig = symbolDeserializer.deserializeIdSignature(symbolData.signatureId)
            assert(!sig.isPackageSignature())
            addIdSignature(sig.topLevelSignature())
        }
    }

    fun addIdSignature(key: IdSignature) {
        reachableTopLevels.add(key)
    }

    fun enqueueAllDeclarations() {
        reachableTopLevels.addAll(fileDeserializer.reversedSignatureIndex.keys)
    }

    fun deserializeAllFileReachableTopLevel() {
        while (reachableTopLevels.isNotEmpty()) {
            val reachableKey = reachableTopLevels.first()

            val existedSymbol = symbolDeserializer.deserializedSymbols[reachableKey]
            if (existedSymbol == null || !existedSymbol.isBound) {
                fileDeserializer.deserializeDeclaration(reachableKey)
            }

            reachableTopLevels.remove(reachableKey)
        }
    }
}

abstract class IrLibraryFile {
    abstract fun irDeclaration(index: Int): ByteArray
    abstract fun type(index: Int): ByteArray
    abstract fun signature(index: Int): ByteArray
    abstract fun string(index: Int): ByteArray
    abstract fun body(index: Int): ByteArray
    abstract fun debugInfo(index: Int): ByteArray?
}

class IrLibraryFileFromKlib(private val klib: IrLibrary, private val fileIndex: Int) : IrLibraryFile() {
    override fun irDeclaration(index: Int): ByteArray = klib.irDeclaration(index, fileIndex)
    override fun type(index: Int): ByteArray = klib.type(index, fileIndex)
    override fun signature(index: Int): ByteArray = klib.signature(index, fileIndex)
    override fun string(index: Int): ByteArray = klib.string(index, fileIndex)
    override fun body(index: Int): ByteArray = klib.body(index, fileIndex)
    override fun debugInfo(index: Int): ByteArray? = klib.debugInfo(index, fileIndex)
}

internal fun IrLibraryFile.deserializeString(index: Int): String = WobblyTF8.decode(string(index))
internal fun IrLibraryFile.deserializeDebugInfo(index: Int): String? = debugInfo(index)?.let { WobblyTF8.decode(it) }

internal fun IrLibraryFile.deserializeFqName(fqn: List<Int>): String =
    fqn.joinToString(".", transform = ::deserializeString)

fun IrLibraryFile.createFile(module: IrModuleFragment, fileProto: ProtoFile): IrFile {
    val fileName = fileProto.fileEntry.name
    val fileEntry = NaiveSourceBasedFileEntryImpl(fileName, fileProto.fileEntry.lineStartOffsetList.toIntArray())
    val fqName = FqName(deserializeFqName(fileProto.fqNameList))
    val packageFragmentDescriptor = EmptyPackageFragmentDescriptor(module.descriptor, fqName)
    val symbol = IrFileSymbolImpl(packageFragmentDescriptor)
    return IrFileImpl(fileEntry, symbol, fqName, module)
}
