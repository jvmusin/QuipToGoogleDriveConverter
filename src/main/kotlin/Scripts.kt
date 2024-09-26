package io.github.jvmusin

object QuipDownloadAll {
    @JvmStatic
    fun main(args: Array<String>) {
        QuipDownloadFileStructure.main(args)
        QuipMarkOriginalFiles.main(args)
        QuipDownloadFiles.main(args)
        QuipDownloadComments.main(args)
    }
}

object ProcessDocuments {
    @JvmStatic
    fun main(args: Array<String>) {
        // TODO: Add opening-closing docs to check they're ok
        // TODO: Add a step here to collect all links and report missing info
        resetUnresolvedLinksFile()
        // Run DriveGenerateIds beforehand to have more links replaced
        if (DriveClientFactory.credentialsExist()) DriveGenerateIds.main(args)
        QuipInsertComments.main(args)
        QuipInsertAuthors.main(args)
        DriveUpdateLinks.main(args)
        dumpCachedUsers() // at this point we can collect all requested users
    }

    private fun dumpCachedUsers() {
        val userRepository = QuipUserRepository.INSTANCE
        val cachedUsers = userRepository.getCachedUsers()
        if (cachedUsers.isEmpty()) return
        val instruction = UnknownUsersInstructionBuilder.build(cachedUsers.map { it.id })
        getLogger().warning("To improve links rebuilding, you need to provide emails for some users\n$instruction")
    }
}

object DriveUploadAll {
    @JvmStatic
    fun main(args: Array<String>) {
        DriveGenerateIds.main(args)
        DriveUploadFiles.main(args)
    }
}
