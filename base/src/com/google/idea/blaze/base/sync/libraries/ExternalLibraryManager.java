/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.sync.libraries;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.sync.BlazeSyncPlugin;
import com.google.idea.blaze.base.sync.SyncListener;
import com.google.idea.blaze.base.sync.SyncMode;
import com.google.idea.blaze.base.sync.SyncResult;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.SyntheticLibrary;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileMoveEvent;
import java.io.File;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * External library manager that rebuilds {@link BlazeExternalSyntheticLibrary}s during sync, and
 * updates individual {@link VirtualFile} entries in response to VFS events.
 */
public class ExternalLibraryManager {
  private final Project project;
  private volatile boolean duringSync;
  private volatile ImmutableMap<
          Class<? extends BlazeExternalLibraryProvider>, BlazeExternalSyntheticLibrary>
      libraries;

  public static ExternalLibraryManager getInstance(Project project) {
    return ServiceManager.getService(project, ExternalLibraryManager.class);
  }

  ExternalLibraryManager(Project project) {
    this.project = project;
    this.duringSync = false;
    this.libraries = ImmutableMap.of();
    VirtualFileManager.getInstance()
        .addVirtualFileListener(
            new VirtualFileListener() {
              @Override
              public void fileCreated(VirtualFileEvent event) {
                libraries.values().forEach(library -> library.updateFile(event.getFile()));
              }

              @Override
              public void fileDeleted(VirtualFileEvent event) {
                libraries.values().forEach(library -> library.removeFile(event.getFile()));
              }

              @Override
              public void fileMoved(VirtualFileMoveEvent event) {
                libraries
                    .values()
                    .forEach(
                        library -> {
                          library.updateFile(event.getFile());
                          library.removeFile(event.getOldParent(), event.getFileName());
                        });
              }
            },
            project);
  }

  @Nullable
  public SyntheticLibrary getLibrary(Class<? extends BlazeExternalLibraryProvider> providerClass) {
    return duringSync ? null : libraries.get(providerClass);
  }

  private void initialize(BlazeProjectData projectData) {
    this.libraries =
        AdditionalLibraryRootsProvider.EP_NAME
            .extensions()
            .filter(BlazeExternalLibraryProvider.class::isInstance)
            .map(BlazeExternalLibraryProvider.class::cast)
            .map(
                provider -> {
                  ImmutableList<File> files = provider.getLibraryFiles(project, projectData);
                  return !files.isEmpty()
                      ? Maps.immutableEntry(
                          provider.getClass(),
                          new BlazeExternalSyntheticLibrary(provider.getLibraryName(), files))
                      : null;
                })
            .filter(Objects::nonNull)
            .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * Sync listener to prevent external libraries from being accessed during sync to avoid spamming
   * {@link VirtualFile#isValid()} errors.
   */
  static class StartSyncListener implements SyncListener {
    @Override
    public void onSyncStart(Project project, BlazeContext context, SyncMode syncMode) {
      ExternalLibraryManager.getInstance(project).duringSync = true;
    }

    @Override
    public void afterSync(
        Project project, BlazeContext context, SyncMode syncMode, SyncResult syncResult) {
      ExternalLibraryManager.getInstance(project).duringSync = false;
    }
  }

  /**
   * Sync plugin to rebuild external libraries during sync to be included in the reindexing
   * operation.
   */
  static class SyncPlugin implements BlazeSyncPlugin {
    @Override
    public void updateProjectStructure(
        Project project,
        BlazeContext context,
        WorkspaceRoot workspaceRoot,
        ProjectViewSet projectViewSet,
        BlazeProjectData blazeProjectData,
        @Nullable BlazeProjectData oldBlazeProjectData,
        ModuleEditor moduleEditor,
        Module workspaceModule,
        ModifiableRootModel workspaceModifiableModel) {
      ExternalLibraryManager manager = ExternalLibraryManager.getInstance(project);
      manager.initialize(blazeProjectData);
      manager.duringSync = false;
    }
  }
}
