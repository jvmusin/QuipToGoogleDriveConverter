package io.github.jvmusin

object UnknownUsersInstructionBuilder {
    fun build(missingUsers: Iterable<String>): String {
        return buildString {
            appendLine("Add the following users a file quip_emails_extra.json in the root of the project")
            appendLine("Format for the file is below (you can omit the \":email\" part)")
            appendLine("{")
            val lastUser = missingUsers.last()
            for (user in missingUsers) {
                val name = QuipUserRepository.INSTANCE.getUserName(user) ?: "FirstName LastName"
                append("\t\"${user}\": \"$name:email@example.com\"")
                if (user != lastUser) append(',')
                appendLine()
            }
            appendLine("}")
        }
    }
}
