/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.tasks.fernflower;

import net.fabricmc.loom.tasks.TForkingJavaExecTask;
import net.fabricmc.loom.tasks.cache.CachedInput;
import net.fabricmc.loom.tasks.cache.CachedInputTask;
import net.fabricmc.loom.util.ConsumingOutputStream;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.process.ExecResult;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import java.io.File;
import java.util.*;
import java.util.function.Supplier;

import static java.text.MessageFormat.format;

/**
 * Created by covers1624 on 9/02/19.
 */
public class FernFlowerTask extends CachedInputTask implements TForkingJavaExecTask {

    private boolean noFork;
    private Object input;
    private Object output;
    private Object lineMapFile;
    private Object libraries;
    private int numThreads = Runtime.getRuntime().availableProcessors();

    @TaskAction
    public void doTask() throws Throwable {
        Map<String, Object> options = new HashMap<>();
        options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
        options.put(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1");
        options.put(IFernflowerPreferences.LOG_LEVEL, "trace");
        getLogging().captureStandardOutput(LogLevel.LIFECYCLE);

        List<String> args = new ArrayList<>();

        options.forEach((k, v) -> args.add(format("-{0}={1}", k, v)));
        args.add(getInput().getAbsolutePath());
        args.add("-o=" + getOutput().getAbsolutePath());
        args.add("-l=" + getLineMapFile().getAbsolutePath());
        args.add("-t=" + getNumThreads());

        //TODO, Decompiler breaks on jemalloc, J9 module-info.class?
        //getLibraries().forEach(f -> args.add("-e=" + f.getAbsolutePath()));

        ServiceRegistry registry = ((ProjectInternal) getProject()).getServices();
        ProgressLoggerFactory factory = registry.get(ProgressLoggerFactory.class);
        ProgressLogger progressGroup = factory.newOperation(getClass()).setDescription("Decompile");
        Supplier<ProgressLogger> loggerFactory = () -> {
            ProgressLogger pl = factory.newOperation(getClass(), progressGroup);
            pl.setDescription("decompile worker");
            pl.started();
            return pl;
        };
        Stack<ProgressLogger> freeLoggers = new Stack<>();
        Map<String, ProgressLogger> inUseLoggers = new HashMap<>();

        progressGroup.started();
        ExecResult result = javaexec(spec -> {
            spec.setMain(ForkedFFExecutor.class.getName());
            spec.jvmArgs("-Xms200m", "-Xmx3G");
            spec.setArgs(args);
            spec.setErrorOutput(System.err);
            spec.setStandardOutput(new ConsumingOutputStream(line -> {
                if (line.startsWith("Listening for transport")) {
                    System.out.println(line);
                    return;
                }
                //System.out.println(line);
                int sepIdx = line.indexOf("::");
                String id = line.substring(0, sepIdx).trim();
                String data = line.substring(sepIdx + 2).trim();

                ProgressLogger logger = inUseLoggers.get(id);

                String[] segs = data.split(" ");
                if (segs[0].equals("waiting")) {
                    if (logger != null) {
                        logger.progress("Idle..");
                        inUseLoggers.remove(id);
                        freeLoggers.push(logger);
                    }
                } else {
                    if (logger == null) {
                        if (!freeLoggers.isEmpty()) {
                            logger = freeLoggers.pop();
                        } else {
                            logger = loggerFactory.get();
                        }
                        inUseLoggers.put(id, logger);
                    }
                    logger.progress(data);
                }
            }));
        });
        inUseLoggers.values().forEach(ProgressLogger::completed);
        freeLoggers.forEach(ProgressLogger::completed);
        progressGroup.completed();

        result.rethrowFailure();
        result.assertNormalExitValue();
    }

    //@formatter:off
    @CachedInput public File getInput() { return getProject().file(input); }
    @OutputFile public File getOutput() { return getProject().file(output); }
    @OutputFile public File getLineMapFile() { return getProject().file(lineMapFile); }
    @CachedInput public FileCollection getLibraries() { return getProject().files(libraries); }
    @CachedInput public int getNumThreads() { return numThreads; }
    public boolean isNoFork() { return noFork; }
    public void setInput(Object input) { this.input = input; }
    public void setOutput(Object output) { this.output = output; }
    public void setLineMapFile(Object lineMapFile) { this.lineMapFile = lineMapFile; }
    public void setLibraries(Object libraries) { this.libraries = libraries; }
    public void setNoFork(boolean noFork) { this.noFork = noFork; }
    public void setNumThreads(int numThreads) { this.numThreads = numThreads; }
    //@formatter:on
}
