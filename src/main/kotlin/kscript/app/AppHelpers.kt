package kscript.app

import kscript.app.ShellUtils.requireInPath
import java.io.*
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.function.Consumer
import kotlin.system.exitProcess


data class ProcessResult(val command: String, val exitCode: Int, val stdout: String, val stderr: String) {

    override fun toString(): String {
        return """
            Exit Code   : ${exitCode}Comand      : ${command}
            Stdout      : ${stdout}
            Stderr      : """.trimIndent() + "\n" + stderr
    }
}

fun evalBash(cmd: String, wd: File? = null,
             stdoutConsumer: Consumer<String> = StringBuilderConsumer(),
             stderrConsumer: Consumer<String> = StringBuilderConsumer()): ProcessResult {
    return runProcess("bash", "-c", cmd,
        wd = wd, stderrConsumer = stderrConsumer, stdoutConsumer = stdoutConsumer)
}


fun runProcess(cmd: String, wd: File? = null): ProcessResult {
    val parts = cmd.split("\\s".toRegex())
    return runProcess(cmd = *parts.toTypedArray(), wd = wd)
}

fun runProcess(vararg cmd: String, wd: File? = null,
               stdoutConsumer: Consumer<String> = StringBuilderConsumer(),
               stderrConsumer: Consumer<String> = StringBuilderConsumer()): ProcessResult {

    try {
        // simplify with https://stackoverflow.com/questions/35421699/how-to-invoke-external-command-from-within-kotlin-code
        val proc = ProcessBuilder(cmd.asList()).
            directory(wd).
            // see https://youtrack.jetbrains.com/issue/KT-20785
            apply { environment()["KOTLIN_RUNNER"] = "" }.
            start();


        // we need to gobble the streams to prevent that the internal pipes hit their respecitive buffer limits, which
        // would lock the sub-process execution (see see https://github.com/holgerbrandl/kscript/issues/55
        // https://stackoverflow.com/questions/14165517/processbuilder-forwarding-stdout-and-stderr-of-started-processes-without-blocki
        val stdoutGobbler = StreamGobbler(proc.inputStream, stdoutConsumer).apply { start() }
        val stderrGobbler = StreamGobbler(proc.errorStream, stderrConsumer).apply { start() }

        val exitVal = proc.waitFor()

        // we need to wait for the gobbler threads or we may loose some output (e.g. in case of short-lived processes
        stderrGobbler.join()
        stdoutGobbler.join()

        return ProcessResult(cmd.joinToString(" "), exitVal, stdoutConsumer.toString(), stderrConsumer.toString())

    } catch (t: Throwable) {
        throw RuntimeException(t)
    }
}


internal class StreamGobbler(private val inputStream: InputStream, private val consumeInputLine: Consumer<String>) : Thread() {


    override fun run() {
        BufferedReader(InputStreamReader(inputStream)).lines().forEach(consumeInputLine)
    }
}

internal open class StringBuilderConsumer : Consumer<String> {
    val sb = StringBuilder()

    override fun accept(t: String) {
        sb.appendln(t)
    }

    override fun toString(): String {
        return sb.toString()
    }
}


object ShellUtils {

    fun isInPath(tool: String) = evalBash("which $tool").stdout.trim().isNotBlank()

    fun requireInPath(tool: String, msg: String = "$tool is not in PATH") = errorIf(!isInPath(tool)) { msg }

}


fun info(msg: String) = System.err.println(msg)


fun infoMsg(msg: String) = System.err.println("[kscript] " + msg)


fun warnMsg(msg: String) = System.err.println("[kscript] [WARN] " + msg)


fun errorMsg(msg: String) = System.err.println("[kscript] [ERROR] " + msg)


fun errorIf(value: Boolean, lazyMessage: () -> Any) {
    if (value) {
        errorMsg(lazyMessage().toString())
        quit(1)
    }
}

fun quit(status: Int): Nothing {
    print(if (status == 0) "true" else "false")
    exitProcess(status)
}

/** see discussion on https://github.com/holgerbrandl/kscript/issues/15*/
fun guessKotlinHome(): String? {
    return evalBash("KOTLIN_RUNNER=1 JAVACMD=echo kotlinc").stdout.run {
        "kotlin.home=([^\\s]*)".toRegex()
            .find(this)?.groups?.get(1)?.value
    }
}


fun createTmpScript(scriptText: String, extension: String = "kts"): File {
    return File(SCRIPT_TEMP_DIR, "scriptlet.${md5(scriptText)}.$extension").apply {
        writeText(scriptText)
    }
}


fun fetchFromURL(scriptURL: String): File {
    val urlHash = md5(scriptURL)
    val scriptText = URL(scriptURL).readText()
    val urlExtension = when {
        scriptURL.endsWith(".kt") -> "kt"
        scriptURL.endsWith(".kts") -> "kts"
        else -> if (scriptText.contains("fun main")) {
            "kt"
        } else {
            "kts"
        }
    }
    val urlCache = File(KSCRIPT_CACHE_DIR, "/url_cache_${urlHash}.$urlExtension")

    if (!urlCache.isFile) {
        urlCache.writeText(scriptText)
    }

    return urlCache
}


fun md5(byteProvider: () -> ByteArray): String {
    // from https://stackoverflow.com/questions/304268/getting-a-files-md5-checksum-in-java
    val md = MessageDigest.getInstance("MD5")
    md.update(byteProvider())

    // disabled to support java9 which dropeed DataTypeConverter
    //    md.update(byteProvider())
    //    val digestInHex = DatatypeConverter.printHexBinary(md.digest()).toLowerCase()

    val digestInHex = bytesToHex(md.digest()).toLowerCase()

    return digestInHex.substring(0, 16)
}

fun md5(msg: String) = md5 { msg.toByteArray() }


fun md5(file: File) = md5 { Files.readAllBytes(Paths.get(file.toURI())) }


// from https://github.com/frontporch/pikitis/blob/master/src/test/kotlin/repacker.tests.kt
private fun bytesToHex(buffer: ByteArray): String {
    val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    val len = buffer.count()
    val result = StringBuffer(len * 2)
    var ix = 0
    while (ix < len) {
        val octet = buffer[ix].toInt()
        val firstIndex = (octet and 0xF0).ushr(4)
        val secondIndex = octet and 0x0F
        result.append(HEX_CHARS[firstIndex])
        result.append(HEX_CHARS[secondIndex])
        ix++
    }
    return result.toString()
}


fun numLines(str: String) = str.split("\r\n|\r|\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray().size


fun launchIdeaWithKscriptlet(scriptFile: File,
                             userArgs: List<String>,
                             dependencies: List<String>,
                             customRepos: List<MavenRepo>,
                             includeURLs: List<URL>,
                             compilerOpts: String): String {
    val intellijCommand = System.getenv("KSCRIPT_IDEA_COMMAND") ?: "idea"
    requireInPath("$intellijCommand", "Could not find '$intellijCommand' in your PATH. You must set the command used to launch your intellij as 'KSCRIPT_IDEA_COMMAND' env property")

    infoMsg("Setting up idea project from ${scriptFile}")

    //    val tmpProjectDir = createTempDir("edit_kscript", suffix="")
    //            .run { File(this, "kscript_tmp_project") }
    //            .apply { mkdir() }


    //  fixme use tmp instead of cachdir. Fails for now because idea gradle import does not seem to like tmp
    val tmpProjectDir = KSCRIPT_CACHE_DIR
        .run { File(this, "kscript_tmp_project__${scriptFile.name}_${System.currentTimeMillis()}") }
        .apply { mkdir() }
    //    val tmpProjectDir = File("/Users/brandl/Desktop/")
    //            .run { File(this, "kscript_tmp_project") }
    //            .apply { mkdir() }

    File(tmpProjectDir, ".idea/runConfigurations/")
        .run {
            mkdirs()
        }
    File(tmpProjectDir, ".idea/runConfigurations/Main.xml").writeText(
        """
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Main" type="BashConfigurationType" factoryName="Bash">
    <option name="INTERPRETER_OPTIONS" value="" />
    <option name="INTERPRETER_PATH" value="kscript" />
    <option name="PROJECT_INTERPRETER" value="false" />
    <option name="WORKING_DIRECTORY" value="" />
    <option name="PARENT_ENVS" value="true" />
    <option name="SCRIPT_NAME" value="${'$'}PROJECT_DIR${'$'}/src/${scriptFile.name}" />
    <option name="PARAMETERS" value="${userArgs.joinToString(" ")}" />
    <module name="" />
    <method v="2" />
  </configuration>
</component>
        """.trimIndent()
    )

    val stringifiedDeps = dependencies.map {
        """
|    implementation("$it")
""".trimMargin()
    }.joinToString("\n")

    fun MavenRepo.stringifiedRepoCredentials(): String{
       return  takeIf { user.isNotBlank() || password.isNotBlank() }?.let {
            """
|        credentials {
|            username = "${it.user}"
|            password = "${it.password}"
|        }
        """
        } ?: ""
    }

    val stringifiedRepos = customRepos.map {
        """
|    maven {
|        url = uri("${it.url}")
         ${it.stringifiedRepoCredentials()}
|    }
    """.trimMargin()
    }.joinToString("\n")

    // We split on space after having joined by space so we have lost some information on how
    // the options where passed. It might cause some issues if some compiler options contain spaces
    // but it's not the case of jvmTarget so we should be fine.
    val opts = compilerOpts.split(" ")
        .filter { it.isNotBlank() }

    var jvmTargetOption: String? = null
    for (i in opts.indices) {
        if (i > 0 && opts[i - 1] == "-jvm-target") {
            jvmTargetOption = opts[i]
        }
    }

    val kotlinOptions = if (jvmTargetOption != null) {
        """
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions { 
        jvmTarget = "$jvmTargetOption"
    }
}
""".trimIndent()
    } else {
        ""
    }

    val gradleScript = """
plugins {
    id("org.jetbrains.kotlin.jvm") version "${KotlinVersion.CURRENT}"
}

repositories {
    mavenLocal()
    jcenter()
$stringifiedRepos
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlin:kotlin-script-runtime")
$stringifiedDeps
}

sourceSets.getByName("main").java.srcDirs("src")
sourceSets.getByName("test").java.srcDirs("src")

$kotlinOptions
    """.trimIndent()

    File(tmpProjectDir, "build.gradle.kts").writeText(gradleScript)

    // also copy/symlink script resource in
    File(tmpProjectDir, "src").run {
        mkdir()

        // https://stackoverflow.com/questions/17926459/creating-a-symbolic-link-with-java
        createSymLink(File(this, scriptFile.name), scriptFile)
        val scriptDir = Paths.get(scriptFile.path).parent

        // also symlink all includes
        includeURLs.distinctBy { it.fileName() }
                .forEach {
                    val symlinkSrcDirAndDestination = when {
                        it.protocol == "file" -> {
                            val includeFile = File(it.toURI())
                            val includeDir = Paths.get(includeFile.path).parent
                            val symlinkRelativePathToScript = File(this, scriptDir.relativize(includeDir).toFile().path)
                            symlinkRelativePathToScript.mkdirs()
                            Pair(symlinkRelativePathToScript, includeFile)
                        }

                        else -> {
                            Pair(this, fetchFromURL(it.toString()))
                        }
                    }
                    createSymLink(File(symlinkSrcDirAndDestination.first, it.fileName()), symlinkSrcDirAndDestination.second)
                }
    }

    val projectPath = tmpProjectDir.absolutePath
    infoMsg("Project set up at $projectPath")

    return "$intellijCommand \"$projectPath\""
}

private fun URL.fileName() = this.toURI().path.split("/").last()

private fun createSymLink(link: File, target: File) {
    try {
        Files.createSymbolicLink(link.toPath(), target.absoluteFile.toPath())
    } catch (e: IOException) {
        errorMsg("Failed to create symbolic link to script. Copying instead...")
        target.copyTo(link)
    }
}


/**
 * Create and use a temporary gradle project to package the compiled script using capsule.
 * See https://github.com/puniverse/capsule
 */
fun packageKscript(scriptJar: File, wrapperClassName: String, dependencies: List<String>, customRepos: List<MavenRepo>, runtimeOptions: String, appName: String, proguardConfig: List<String>?) {
    requireInPath("gradle", "gradle is required to package kscripts")

    infoMsg("Packaging script '$appName' into standalone executable...")


    val tmpProjectDir = KSCRIPT_CACHE_DIR
        .run { File(this, "kscript_tmp_project__${scriptJar.name}_${System.currentTimeMillis()}") }
        .apply { mkdir() }

    val stringifiedDeps = dependencies.map { "    compile \"$it\"" }.joinToString("\n")
    val stringifiedRepos = customRepos.map { "    maven {\n        url '${it.url}'\n    }\n" }.joinToString("\n")

    val jvmOptions = runtimeOptions.split(" ")
        .filter { it.startsWith("-J") }
        .map { it.removePrefix("-J") }
        .map { '"' + it + '"' }
        .joinToString(", ")

    // https://shekhargulati.com/2015/09/10/gradle-tip-using-gradle-plugin-from-local-maven-repository/

    createGradleFile(proguardConfig, stringifiedRepos, stringifiedDeps, scriptJar, wrapperClassName, tmpProjectDir, appName, jvmOptions)


    val pckgedJar = File(Paths.get("").toAbsolutePath().toFile(), appName).absoluteFile

    // create exec_header to allow for direction execution (see http://www.capsule.io/user-guide/#really-executable-capsules)
    // from https://github.com/puniverse/capsule/blob/master/capsule-util/src/main/resources/capsule/execheader.sh
    val execHeaderFile = File(tmpProjectDir, "exec_header.sh").also {
        it.writeText("""#!/usr/bin/env bash
exec java -jar ${'$'}0 "${'$'}@"
""")
    }

    createProguardFile(tmpProjectDir, proguardConfig)

    val pckgResult = evalBash("cd '${tmpProjectDir}' && gradle ${if (proguardConfig != null) "shadowJar proguard" else "simpleCapsule"}")

    with(pckgResult) {
        kscript.app.errorIf(exitCode != 0) { "packaging of '$appName' failed:\n$pckgResult" }
    }

    pckgedJar.delete()
    if (proguardConfig != null) {
        execHeaderFile.let {
            it.appendBytes(File(tmpProjectDir, "build/libs/${tmpProjectDir.name}-proguarded.jar").readBytes())
            it.copyTo(pckgedJar, true).setExecutable(true)
        }
    } else {
        File(tmpProjectDir, "build/libs/${appName}").copyTo(pckgedJar, true).setExecutable(true)
    }

    infoMsg("Finished packaging into ${pckgedJar}")
}

private fun createGradleFile(proguardConfig: List<String>?, stringifiedRepos: String, stringifiedDeps: String, scriptJar: File, wrapperClassName: String, tmpProjectDir: File, appName: String, jvmOptions: String) {
    File(tmpProjectDir, "build.gradle").writeText("""
${proguardBuildScripts(proguardConfig)}
plugins {
    id "org.jetbrains.kotlin.jvm" version "${KotlinVersion.CURRENT}"
    ${if (proguardConfig != null) "id \"com.github.johnrengelman.shadow\" version \"6.0.0\"" else "id \"it.gianluz.capsule\" version \"1.0.3\""}
}

repositories {
    mavenLocal()
    jcenter()
$stringifiedRepos
}

dependencies {
    compile "org.jetbrains.kotlin:kotlin-stdlib"
$stringifiedDeps

    compile group: 'org.jetbrains.kotlin', name: 'kotlin-script-runtime', version: '${KotlinVersion.CURRENT}'

    // https://stackoverflow.com/questions/20700053/how-to-add-local-jar-file-dependency-to-build-gradle-file
    compile files('${scriptJar.invariantSeparatorsPath}')
}

${gradleTasks(proguardConfig, wrapperClassName, tmpProjectDir, appName, jvmOptions)}""".trimIndent()) }

private fun gradleTasks(proguardConfig: List<String>?, wrapperClassName: String, tmpProjectDir: File, appName: String, jvmOptions: String): String {
    return if (proguardConfig != null) """
jar {
    manifest {
        attributes 'Main-Class': '$wrapperClassName'
    }
}

task ('proguard', type: proguard.gradle.ProGuardTask) {

  configuration("proguard.pro")

  injars  'build/libs/${tmpProjectDir.name}-all.jar'
  outjars 'build/libs/${tmpProjectDir.name}-proguarded.jar'

  // Automatically handle the Java version of this build.
  if (System.getProperty('java.version').startsWith('1.')) {
    // Before Java 9, the runtime classes were packaged in a single jar file.
    libraryjars "${"$"}{System.getProperty('java.home')}/lib/rt.jar"
  } else {
    // As of Java 9, the runtime classes are packaged in modular jmod files.
    libraryjars "${"$"}{System.getProperty('java.home')}/jmods/java.base.jmod", jarfilter: '!**.jar', filter: '!module-info.class'
    //libraryjars ${"$"}{System.getProperty('java.home')}/jmods/....."
  }
}
""" else """

task simpleCapsule(type: FatCapsule){
  applicationClass '$wrapperClassName'

  archiveName '$appName'

  // http://www.capsule.io/user-guide/#really-executable-capsules
  reallyExecutable

  capsuleManifest {
    jvmArgs = [$jvmOptions]
    //args = []
    //systemProperties['java.awt.headless'] = true
  }
}
"""
}

private fun proguardBuildScripts(proguardConfig: List<String>?): String {
    return if (proguardConfig != null) """
buildscript { 
    repositories {
           google()
           jcenter()
    }
    dependencies {
        classpath 'com.guardsquare:proguard-gradle:7.0.0'
        classpath "com.github.jengelman.gradle.plugins:shadow:6.0.0"
    }
}
""" else ""
}

private fun createProguardFile(tmpProjectDir: File, proguardConfig: List<String>?) {
    proguardConfig?.let {
        File(tmpProjectDir, "proguard.pro").writeText(
                """
### Custom project based configuration

${proguardConfig?.joinToString(separator = "\n")}

### Default app configuration
#
# This ProGuard configuration file illustrates how to process applications.
# Usage:
#     java -jar proguard.jar @applications.pro
#

-verbose

-dontwarn

# Save the obfuscation mapping to a file, so you can de-obfuscate any stack
# traces later on. Keep a fixed source file attribute and all line number
# tables to get line numbers in the stack traces.
# You can comment this out if you're not interested in stack traces.

-printmapping out.map
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Preserve all annotations.

-keepattributes *Annotation*

# You can print out the seeds that are matching the keep options below.

#-printseeds out.seeds

# Preserve all public applications.

-keepclasseswithmembers public class * {
    public static void main(java.lang.String[]);
}

# Preserve all native method names and the names of their classes.

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Preserve the special static methods that are required in all enumeration
# classes.

-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Explicitly preserve all serialization members. The Serializable interface
# is only a marker interface, so it wouldn't save them.
# You can comment this out if your application doesn't use serialization.
# If your code contains serializable classes that have to be backward
# compatible, please refer to the manual.

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Your application may contain more items that need to be preserved;
# typically classes that are dynamically created using Class.forName:

# -keep public class com.example.MyClass
# -keep public interface com.example.MyInterface
# -keep public class * implements com.example.MyInterface
    """.trimIndent())
    }
}
