// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.diagnostic;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Attachment;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class IdeaFreezeReporter {
  public IdeaFreezeReporter() {
    ApplicationManager.getApplication().getMessageBus().connect().subscribe(IdePerformanceListener.TOPIC, new IdePerformanceListener() {
      final List<ThreadDump> myCurrentDumps = new ArrayList<>();
      List<StackTraceElement> myStacktraceCommonPart = null;

      @Override
      public void dumpedThreads(@NotNull File toFile, @NotNull ThreadDump dump) {
        myCurrentDumps.add(dump);
        StackTraceElement[] edtStack = dump.getEDTStackTrace();
        if (edtStack != null) {
          if (myStacktraceCommonPart == null) {
            myStacktraceCommonPart = ContainerUtil.newArrayList(edtStack);
          }
          else {
            myStacktraceCommonPart = PerformanceWatcher.getStacktraceCommonPart(myStacktraceCommonPart, edtStack);
          }
        }
      }

      @Override
      public void uiFreezeFinished(int lengthInSeconds) {
        if (Registry.is("performance.watcher.freeze.report") &&
            !ContainerUtil.isEmpty(myCurrentDumps) &&
            !ContainerUtil.isEmpty(myStacktraceCommonPart)) {
          int size = Math.min(myCurrentDumps.size(), 20); // report up to 20 dumps
          Attachment[] attachments = new Attachment[size];
          for (int i = 0; i < size; i++) {
            Attachment attachment = new Attachment("dump-" + i, myCurrentDumps.get(i).getRawDump());
            attachment.setIncluded(true);
            attachments[i] = attachment;
          }
          MessagePool.getInstance().addIdeFatalMessage(LogMessage.createEvent(new Freeze(myStacktraceCommonPart),
                                                                              "Freeze for " + lengthInSeconds + " seconds", attachments));
        }
        myCurrentDumps.clear();
        myStacktraceCommonPart = null;
      }
    });
  }
}
