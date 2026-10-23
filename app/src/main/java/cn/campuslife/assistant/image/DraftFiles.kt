package cn.campuslife.assistant.image

import java.io.File

object DraftFiles {
    fun commit(newPhoto: File,oldPhoto: File?,folder: File,savePointer: (String)->Unit) {
        require(newPhoto.canonicalFile.parentFile==folder.canonicalFile)
        savePointer(newPhoto.name)
        if(oldPhoto!=null && oldPhoto!=newPhoto && oldPhoto.canonicalFile.parentFile==folder.canonicalFile) oldPhoto.delete()
    }
}
