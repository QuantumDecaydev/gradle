/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.tasks;

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Unroll;

class TaskDependencyInferenceIntegrationTest extends AbstractIntegrationSpec {
    def "dependency declared using task provider implies dependency on task"() {
        buildFile << """
            // verify that eager and lazy providers work
            def providerA = tasks.register("a")
            tasks.create("b")
            def providerB = tasks.named("b")
            tasks.register("c") {
                dependsOn providerA
                dependsOn providerB
            }
        """

        when:
        run("c")

        then:
        result.assertTasksExecuted(":a", ":b", ":c")
    }

    def "dependency declared using mapped task provider implies dependency on task and does not run mapping function"() {
        buildFile << """
            def providerA = tasks.register("a")
            tasks.create("b")
            def providerB = tasks.named("b")
            tasks.register("c") {
                dependsOn providerA.map { throw new RuntimeException() }
                dependsOn providerB.map { throw new RuntimeException() }
            }
        """

        when:
        run("c")

        then:
        result.assertTasksExecuted(":a", ":b", ":c")
    }

    def "dependency declared using provider that returns task implies dependency on task"() {
        buildFile << """
            def a = tasks.create("a")
            def provider = provider { a }
            tasks.register("b") {
                dependsOn provider
            }
        """

        when:
        run("b")

        then:
        result.assertTasksExecuted(":a", ":b")
    }

    def "dependency declared using provider mapping that returns task implies dependency on task"() {
        buildFile << """
            def a = tasks.create("a")
            def provider = provider { "a" }
            tasks.register("b") {
                dependsOn provider.map { tasks.getByName(it) }
            }
        """

        when:
        run("b")

        then:
        result.assertTasksExecuted(":a", ":b")
    }

    def "dependency declared using task output file property implies dependency on task"() {
        taskTypeWithOutputFileProperty()
        buildFile << """
            def task = tasks.create("a", OutputFileTask) {
                outFile = file("a.txt")
            }
            tasks.register("b") {
                dependsOn task.outFile
            }
        """

        when:
        run("b")

        then:
        result.assertTasksExecuted(":a", ":b")
    }

    def "dependency declared using mapped task output file property implies dependency on task and does not run mapping function"() {
        taskTypeWithOutputFileProperty()
        buildFile << """
            def task = tasks.create("a", OutputFileTask) {
                outFile = file("a.txt")
            }            
            tasks.register("b") {
                dependsOn task.outFile.map { throw new RuntimeException() }
            }
        """

        when:
        run("b")

        then:
        result.assertTasksExecuted(":a", ":b")
    }

    def "dependency declared using provider that returns task name implies dependency on task"() {
        buildFile << """
            def a = tasks.create("a")
            def provider = provider { "a" }
            tasks.register("b") {
                dependsOn provider
            }
        """

        when:
        run("b")

        then:
        result.assertTasksExecuted(":a", ":b")
    }

    def "dependency declared using mapped provider that returns task name implies dependency on task"() {
        buildFile << """
            def a = tasks.create("a")
            def provider = provider { a }.map { it.name }
            tasks.register("b") {
                dependsOn provider
            }
        """

        when:
        run("b")

        then:
        result.assertTasksExecuted(":a", ":b")
    }

    @Unroll
    def "dependency declared using provider that returns #value fails"() {
        buildFile << """
            def provider = provider { $value }
            tasks.register("b") {
                dependsOn provider
            }
        """

        when:
        fails("b")

        then:
        failure.assertHasDescription("Could not determine the dependencies of task ':b'.")
        def val = displayName.call(temporaryFolder)
        failure.assertHasCause("""Cannot convert ${val} to a task.
The following types/formats are supported:
  - A String or CharSequence task name or path
  - A TaskReference instance
  - A Task instance
  - A Buildable instance
  - A TaskDependency instance
  - A Provider that represents a task output
  - A Provider instance that returns any of these types
  - A Closure instance that returns any of these types
  - A Callable instance that returns any of these types
  - An Iterable, Collection, Map or array instance that contains any of these types""")

        where:
        // the closures are because spock calls these expressions early before the test dir fixture is ready
        value                               | displayName
        "12"                                | { "12" }
        "file('123')"                       | { it.file('123') }
        "layout.projectDirectory"           | { it.file(".") }
        "layout.buildDirectory.file('123')" | { it.file('build/123') }
    }

    def "input file collection containing task provider implies dependency on all outputs of the task"() {
        taskTypeWithMultipleOutputFiles()
        taskTypeWithInputFilesProperty()
        buildFile << """
            def provider = tasks.register("a", OutputFilesTask) {
                out1 = file("file1.txt")
                out2 = file("file2.txt")
            }
            tasks.register("b", InputFilesTask) {
                inFiles.from(provider)
                outFile = file("out.txt")
            }
        """

        when:
        run("b")

        then:
        result.assertTasksExecuted(":a", ":b")
        file("out.txt").text == "1,2"
    }

    def "input file collection containing mapped task provider implies dependency on a specific output of the task"() {
        taskTypeWithMultipleOutputFiles()
        taskTypeWithInputFilesProperty()
        buildFile << """
            def provider = tasks.register("a", OutputFilesTask) {
                out1 = file("file1.txt")
                out2 = file("file2.txt")
            }
            tasks.register("b", InputFilesTask) {
                inFiles.from provider.map { project.layout.projectDir.file(it.out1.absolutePath) }
                outFile = file("out.txt")
            }
        """

        when:
        run("b")

        then:
        result.assertTasksExecuted(":a", ":b")
        file("out.txt").text == "1"
    }

    def "input file property with value of mapped task provider implies dependency on a specific output of the task"() {
        taskTypeWithMultipleOutputFiles()
        taskTypeWithInputFileProperty()
        buildFile << """
            def provider = tasks.register("a", OutputFilesTask) {
                out1 = file("file1.txt")
                out2 = file("file2.txt")
            }
            tasks.register("b", InputFileTask) {
                inFile = provider.map { project.layout.projectDir.file(it.out1.absolutePath) }
                outFile = file("out.txt")
            }
        """

        when:
        run("b")

        then:
        result.assertTasksExecuted(":a", ":b")
        file("out.txt").text == "1"
    }

    def "input file collection containing mapped task output property implies dependency on a specific output of the task"() {
        taskTypeWithMultipleOutputFileProperties()
        taskTypeWithInputFilesProperty()
        buildFile << """
            def a = tasks.create("a", OutputFilesTask) {
                out1 = file("file1.txt")
                out2 = file("file2.txt")
            }
            tasks.register("b", InputFilesTask) {
                inFiles.from a.out1.map { it }
                outFile = file("out.txt")
            }
        """

        when:
        run("b")

        then:
        result.assertTasksExecuted(":a", ":b")
        file("out.txt").text == "1"
    }

    @Unroll
    def "input file collection containing provider that returns #value does not imply task dependency"() {
        taskTypeWithInputFilesProperty()
        buildFile << """
            tasks.register("b", InputFilesTask) {
                inFiles.from provider { ${value} }
                outFile = file("out.txt")
            }
        """
        file("in.txt") << "1"

        when:
        run("b")

        then:
        result.assertTasksExecuted(":b")
        file("out.txt").text == "1"

        where:
        value                                     | _
        "file('in.txt')"                          | _
        "layout.projectDirectory.file('in.txt')"  | _
        "layout.buildDirectory.file('../in.txt')" | _
    }

    def taskTypeWithOutputFileProperty() {
        buildFile << """
            class OutputFileTask extends DefaultTask {
                @OutputFile
                final RegularFileProperty outFile = project.objects.fileProperty()
                @TaskAction
                def go() {
                    outFile.get().asFile.text = "1"
                }
            }
        """
    }

    def taskTypeWithMultipleOutputFiles() {
        buildFile << """
            // Not using properties
            class OutputFilesTask extends DefaultTask {
                @OutputFile
                File out1
                @OutputFile
                File out2
                @TaskAction
                def go() {
                    out1.text = "1"
                    out2.text = "2"
                }
            }
        """
    }

    def taskTypeWithMultipleOutputFileProperties() {
        buildFile << """
            class OutputFilesTask extends DefaultTask {
                @OutputFile
                final RegularFileProperty out1 = project.objects.fileProperty()
                @OutputFile
                final RegularFileProperty out2 = project.objects.fileProperty()
                @TaskAction
                def go() {
                    out1.get().asFile.text = "1"
                    out2.get().asFile.text = "2"
                }
            }
        """
    }

    def taskTypeWithInputFileProperty() {
        buildFile << """
            class InputFileTask extends DefaultTask {
                @InputFile
                final RegularFileProperty inFile = project.objects.fileProperty()
                @OutputFile
                final RegularFileProperty outFile = project.objects.fileProperty()
                @TaskAction
                def go() {
                    outFile.get().asFile.text = inFile.get().asFile.text
                }
            }
        """
    }

    def taskTypeWithInputFilesProperty() {
        buildFile << """
            class InputFilesTask extends DefaultTask {
                @InputFiles
                final inFiles = project.files()
                @OutputFile
                final RegularFileProperty outFile = project.objects.fileProperty()
                @TaskAction
                def go() {
                    outFile.get().asFile.text = inFiles*.text.sort().join(',')
                }
            }
        """
    }
}