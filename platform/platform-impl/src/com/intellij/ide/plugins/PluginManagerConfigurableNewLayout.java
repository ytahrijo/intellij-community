// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins;

import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.plugins.newui.*;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.updateSettings.impl.PluginDownloader;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.ThrowableNotNullFunction;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.components.labels.LinkListener;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.net.HttpConfigurable;
import com.intellij.util.ui.JBDimension;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.StatusText;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

/**
 * @author Alexander Lobas
 */
public class PluginManagerConfigurableNewLayout
  implements SearchableConfigurable, Configurable.NoScroll, Configurable.NoMargin, Configurable.TopComponentProvider,
             PluginManagerConfigurableInfo {

  private static final int MARKETPLACE_TAB = 0;
  private static final int INSTALLED_TAB = 1;

  private TabHeaderComponent myTabHeaderComponent;
  private PluginManagerConfigurableNew.CountTabName myInstalledTabName;
  private MultiPanel myCardPanel;

  private PluginsTab myMarketplaceTab;
  private PluginsTab myInstalledTab;

  private PluginsGroupComponentWithProgress myMarketplacePanel;
  private PluginsGroupComponent myInstalledPanel;

  private Runnable myMarketplaceRunnable;

  private SearchResultPanel myMarketplaceSearchPanel;
  private SearchResultPanel myInstalledSearchPanel;

  private LinkListener<IdeaPluginDescriptor> myNameListener;
  private LinkListener<String> mySearchListener;

  private final LinkLabel<Object> myUpdateAll = new LinkLabel<>("Update All", null);
  private final JLabel myUpdateCounter = new PluginManagerConfigurableTreeRenderer.CountComponent();

  private final MyPluginModel myPluginModel = new MyPluginModel() {
    @Override
    public List<IdeaPluginDescriptor> getAllRepoPlugins() {
      return getPluginRepositories();
    }
  };

  private Runnable myShutdownCallback;

  private PluginUpdatesService myPluginUpdatesService;

  private List<IdeaPluginDescriptor> myAllRepositoriesList;
  private Map<String, IdeaPluginDescriptor> myAllRepositoriesMap;
  private Map<String, List<IdeaPluginDescriptor>> myCustomRepositoriesMap;
  private final Object myRepositoriesLock = new Object();
  private List<String> myAllTagSorted;

  @NotNull
  @Override
  public String getId() {
    return PluginManagerConfigurableNew.ID;
  }

  @Override
  public String getDisplayName() {
    return IdeBundle.message("title.plugins");
  }

  @NotNull
  @Override
  public Component getCenterComponent(@NotNull TopComponentController controller) {
    myPluginModel.setTopController(controller);
    return myTabHeaderComponent;
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    myTabHeaderComponent = new TabHeaderComponent(createGearActions(), index -> {
      myCardPanel.select(index, true);
      storeSelectionTab(index);
    });

    myTabHeaderComponent.addTab("Marketplace");
    myTabHeaderComponent.addTab(myInstalledTabName = new PluginManagerConfigurableNew.CountTabName(myTabHeaderComponent, "Installed") {
      @Override
      public void setCount(int count) {
        super.setCount(count);
        myUpdateAll.setVisible(count > 0);
        myUpdateCounter.setText(String.valueOf(count));
        myUpdateCounter.setVisible(count > 0);
      }
    });

    myPluginUpdatesService =
      PluginUpdatesService.connectConfigurable(countValue -> myInstalledTabName.setCount(countValue == null ? 0 : countValue));
    myPluginModel.setPluginUpdatesService(myPluginUpdatesService);

    myNameListener = (aSource, aLinkData) -> {
      // TODO: unused
    };
    mySearchListener = (aSource, aLinkData) -> {
      // TODO: unused
    };

    createMarketplaceTab();
    createInstalledTab();

    myCardPanel = new MultiPanel() {
      @Override
      protected JComponent create(Integer key) {
        if (key == MARKETPLACE_TAB) {
          return myMarketplaceTab.createPanel();
        }
        if (key == INSTALLED_TAB) {
          return myInstalledTab.createPanel();
        }
        return super.create(key);
      }
    };
    myCardPanel.setMinimumSize(new JBDimension(580, 380));

    int selectionTab = getStoredSelectionTab();
    myTabHeaderComponent.setSelection(selectionTab);
    myCardPanel.select(selectionTab, true);

    return myCardPanel;
  }

  @NotNull
  private DefaultActionGroup createGearActions() {
    DefaultActionGroup actions = new DefaultActionGroup();
    actions.add(new DumbAwareAction("Manage Plugin Repositories...") {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        if (ShowSettingsUtil.getInstance().editConfigurable(myCardPanel, new PluginHostsConfigurable())) {
          resetPanels();
        }
      }
    });
    actions.add(new DumbAwareAction(IdeBundle.message("button.http.proxy.settings")) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        if (HttpConfigurable.editConfigurable(myCardPanel)) {
          resetPanels();
        }
      }
    });
    actions.addSeparator();
    actions.add(new DumbAwareAction("Install Plugin from Disk...") {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        InstalledPluginsManagerMain.chooseAndInstall(myPluginModel, myCardPanel, pair -> {
          myPluginModel.appendOrUpdateDescriptor(pair.second);

          boolean select = myInstalledPanel == null;

          if (myTabHeaderComponent.getSelectionTab() != 1) {
            myTabHeaderComponent.setSelectionWithEvents(1);
          }

          myInstalledTab.clearSearchPanel("");

          if (select) {
            for (UIPluginGroup group : myInstalledPanel.getGroups()) {
              CellPluginComponent component = group.findComponent(pair.second);
              if (component != null) {
                myInstalledPanel.setSelection(component);
                break;
              }
            }
          }
        });
      }
    });
    actions.addSeparator();
    actions.add(new ChangePluginStateAction(false));
    actions.add(new ChangePluginStateAction(true));

    return actions;
  }

  private void resetPanels() {
    synchronized (myRepositoriesLock) {
      myAllRepositoriesList = null;
      myAllRepositoriesMap = null;
      myCustomRepositoriesMap = null;
    }

    myPluginUpdatesService.recalculateUpdates();

    if (myMarketplacePanel == null) {
      return;
    }

    int selectionTab = myTabHeaderComponent.getSelectionTab();
    if (selectionTab == MARKETPLACE_TAB) {
      myMarketplaceRunnable.run();
    }
    else {
      myMarketplacePanel.setVisibleRunnable(myMarketplaceRunnable);
    }
  }

  private static int getStoredSelectionTab() {
    int value = PropertiesComponent.getInstance().getInt(PluginManagerConfigurableNew.SELECTION_TAB_KEY, MARKETPLACE_TAB);
    return value < MARKETPLACE_TAB || value > INSTALLED_TAB ? MARKETPLACE_TAB : value;
  }

  private static void storeSelectionTab(int value) {
    PropertiesComponent.getInstance().setValue(PluginManagerConfigurableNew.SELECTION_TAB_KEY, value, MARKETPLACE_TAB);
  }

  private void createMarketplaceTab() {
    myMarketplaceTab = new PluginsTab() {
      @NotNull
      @Override
      protected PluginDetailsPageComponent createDetailsPanel(@NotNull LinkListener<Object> searchListener) {
        PluginDetailsPageComponent detailPanel = new PluginDetailsPageComponent(myPluginModel, searchListener, true);
        myPluginModel.addDetailPanel(detailPanel);
        return detailPanel;
      }

      @NotNull
      @Override
      protected JComponent createPluginsPanel(@NotNull Consumer<PluginsGroupComponent> selectionListener) {
        myMarketplacePanel = new PluginsGroupComponentWithProgress(new PluginListLayout(), new MultiSelectionEventHandler(), myNameListener,
                                                                   PluginManagerConfigurableNewLayout.this.mySearchListener,
                                                                   descriptor -> new NewListPluginComponent(myPluginModel, descriptor,
                                                                                                            true));

        myMarketplacePanel.setSelectionListener(selectionListener);
        PluginManagerConfigurableNew.registerCopyProvider(myMarketplacePanel);

        Runnable runnable = () -> {
          List<PluginsGroup> groups = new ArrayList<>();

          try {
            Pair<Map<String, IdeaPluginDescriptor>, Map<String, List<IdeaPluginDescriptor>>> pair = loadPluginRepositories();
            Map<String, IdeaPluginDescriptor> allRepositoriesMap = pair.first;
            Map<String, List<IdeaPluginDescriptor>> customRepositoriesMap = pair.second;

            try {
              addGroup(groups, allRepositoriesMap, "Featured", "is_featured_search=true", "sortBy:featured");
              addGroup(groups, allRepositoriesMap, "New and Updated", "orderBy=update+date", "sortBy:updated");
              addGroup(groups, allRepositoriesMap, "Top Downloads", "orderBy=downloads", "sortBy:downloads");
              addGroup(groups, allRepositoriesMap, "Top Rated", "orderBy=rating", "sortBy:rating");
            }
            catch (IOException e) {
              PluginManagerMain.LOG
                .info("Main plugin repository is not available ('" + e.getMessage() + "'). Please check your network settings.");
            }

            for (String host : UpdateSettings.getInstance().getPluginHosts()) {
              List<IdeaPluginDescriptor> allDescriptors = customRepositoriesMap.get(host);
              if (allDescriptors != null) {
                addGroup(groups, "Repository: " + host, "repository:\"" + host + "\"", descriptors -> {
                  int allSize = allDescriptors.size();
                  descriptors.addAll(ContainerUtil.getFirstItems(allDescriptors, PluginManagerConfigurableNew.ITEMS_PER_GROUP));
                  PluginsGroup.sortByName(descriptors);
                  return allSize > PluginManagerConfigurableNew.ITEMS_PER_GROUP;
                });
              }
            }
          }
          catch (IOException e) {
            PluginManagerMain.LOG.info(e);
          }
          finally {
            ApplicationManager.getApplication().invokeLater(() -> {
              myMarketplacePanel.stopLoading();
              PluginLogo.startBatchMode();

              for (PluginsGroup group : groups) {
                myMarketplacePanel.addGroup(group);
              }

              PluginLogo.endBatchMode();
              myMarketplacePanel.doLayout();
              myMarketplacePanel.initialSelection();
            }, ModalityState.any());
          }
        };

        myMarketplaceRunnable = () -> {
          myMarketplacePanel.clear();
          myMarketplacePanel.startLoading();
          ApplicationManager.getApplication().executeOnPooledThread(runnable);
        };

        myMarketplacePanel.getEmptyText().setText("Marketplace plugins are not loaded.")
          .appendSecondaryText("Check the internet connection and ", StatusText.DEFAULT_ATTRIBUTES, null)
          .appendSecondaryText("refresh", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, e -> myMarketplaceRunnable.run());

        ApplicationManager.getApplication().executeOnPooledThread(runnable);
        return createScrollPane(myMarketplacePanel, false);
      }

      @Override
      protected void updateMainSelection(@NotNull Consumer<PluginsGroupComponent> selectionListener) {
        selectionListener.accept(myMarketplacePanel);
      }

      @NotNull
      @Override
      protected SearchResultPanel createSearchPanel(@NotNull Consumer<PluginsGroupComponent> selectionListener,
                                                    @NotNull PluginSearchTextField searchTextField) {
        SearchPopupController trendingController = new SearchPopupController(searchTextField) {
          @NotNull
          @Override
          protected List<String> getAttributes() {
            List<String> attributes = new ArrayList<>();
            attributes.add("tag:");
            if (!UpdateSettings.getInstance().getPluginHosts().isEmpty()) {
              attributes.add("repository:");
            }
            attributes.add("sortBy:");
            return attributes;
          }

          @Nullable
          @Override
          protected List<String> getValues(@NotNull String attribute) {
            switch (attribute) {
              case "tag:":
                if (ContainerUtil.isEmpty(myAllTagSorted)) {
                  Set<String> allTags = new HashSet<>();
                  for (IdeaPluginDescriptor descriptor : getPluginRepositories()) {
                    if (descriptor instanceof PluginNode) {
                      List<String> tags = ((PluginNode)descriptor).getTags();
                      if (!ContainerUtil.isEmpty(tags)) {
                        allTags.addAll(tags);
                      }
                    }
                  }
                  myAllTagSorted = ContainerUtil.sorted(allTags, String::compareToIgnoreCase);
                }
                return myAllTagSorted;
              case "repository:":
                return UpdateSettings.getInstance().getPluginHosts();
              case "sortBy:":
                return ContainerUtil.list("downloads", "name", "rating", "featured", "updated");
            }
            return null;
          }

          @Override
          protected void showPopupForQuery() {
            hidePopup();
          }

          @Override
          protected void handleEnter() {
            if (!searchTextField.getText().isEmpty()) {
              handleTrigger("marketplace.suggest.popup.enter");
            }
          }

          @Override
          protected void handlePopupListFirstSelection() {
            handleTrigger("marketplace.suggest.popup.select");
          }

          private void handleTrigger(@NonNls String key) {
            if (myPopup != null && myPopup.type == SearchPopup.Type.SearchQuery) {
              FeatureUsageTracker.getInstance().triggerFeatureUsed(key);
            }
          }
        };

        PluginsGroupComponentWithProgress panel =
          new PluginsGroupComponentWithProgress(new PluginListLayout(), new MultiSelectionEventHandler(), myNameListener,
                                                PluginManagerConfigurableNewLayout.this.mySearchListener,
                                                descriptor -> new NewListPluginComponent(myPluginModel, descriptor, true));

        panel.setSelectionListener(selectionListener);
        PluginManagerConfigurableNew.registerCopyProvider(panel);

        myMarketplaceSearchPanel =
          new SearchResultPanel(trendingController, panel, 0, 0) {
            @Override
            protected void handleQuery(@NotNull String query, @NotNull PluginsGroup result) {
              try {
                Pair<Map<String, IdeaPluginDescriptor>, Map<String, List<IdeaPluginDescriptor>>> p = loadPluginRepositories();
                Map<String, IdeaPluginDescriptor> allRepositoriesMap = p.first;
                Map<String, List<IdeaPluginDescriptor>> customRepositoriesMap = p.second;

                SearchQueryParser.Trending parser = new SearchQueryParser.Trending(query);

                if (!parser.repositories.isEmpty()) {
                  for (String repository : parser.repositories) {
                    List<IdeaPluginDescriptor> descriptors = customRepositoriesMap.get(repository);
                    if (descriptors == null) {
                      continue;
                    }
                    if (parser.searchQuery == null) {
                      result.descriptors.addAll(descriptors);
                    }
                    else {
                      for (IdeaPluginDescriptor descriptor : descriptors) {
                        if (StringUtil.containsIgnoreCase(descriptor.getName(), parser.searchQuery)) {
                          result.descriptors.add(descriptor);
                        }
                      }
                    }
                  }
                  result.sortByName();
                  return;
                }

                for (String pluginId : PluginManagerConfigurableNew
                  .requestToPluginRepository(PluginManagerConfigurableNew.createSearchUrl(parser.getUrlQuery(), 10000),
                                             PluginManagerConfigurableNew.forceHttps())) {
                  IdeaPluginDescriptor descriptor = allRepositoriesMap.get(pluginId);
                  if (descriptor != null) {
                    result.descriptors.add(descriptor);
                  }
                }

                if (parser.searchQuery != null) {
                  String builtinUrl = ApplicationInfoEx.getInstanceEx().getBuiltinPluginsUrl();
                  List<IdeaPluginDescriptor> builtinList = new ArrayList<>();

                  for (Map.Entry<String, List<IdeaPluginDescriptor>> entry : customRepositoriesMap.entrySet()) {
                    List<IdeaPluginDescriptor> descriptors = entry.getKey().equals(builtinUrl) ? builtinList : result.descriptors;
                    for (IdeaPluginDescriptor descriptor : entry.getValue()) {
                      if (StringUtil.containsIgnoreCase(descriptor.getName(), parser.searchQuery)) {
                        descriptors.add(descriptor);
                      }
                    }
                  }

                  result.descriptors.addAll(0, builtinList);
                }
              }
              catch (IOException e) {
                PluginManagerMain.LOG.info(e);

                ApplicationManager.getApplication().invokeLater(() -> myPanel.getEmptyText().setText("Search result are not loaded.")
                  .appendSecondaryText("Check the internet connection.", StatusText.DEFAULT_ATTRIBUTES, null), ModalityState.any());
              }
            }
          };

        return myMarketplaceSearchPanel;
      }
    };
  }

  private void createInstalledTab() {
    myInstalledTab = new PluginsTab() {
      @NotNull
      @Override
      protected PluginDetailsPageComponent createDetailsPanel(@NotNull LinkListener<Object> searchListener) {
        PluginDetailsPageComponent detailPanel = new PluginDetailsPageComponent(myPluginModel, searchListener, false);
        myPluginModel.addDetailPanel(detailPanel);
        return detailPanel;
      }

      @NotNull
      @Override
      protected JComponent createPluginsPanel(@NotNull Consumer<PluginsGroupComponent> selectionListener) {
        myInstalledPanel = new PluginsGroupComponent(new PluginListLayout(), new MultiSelectionEventHandler(), myNameListener,
                                                     PluginManagerConfigurableNewLayout.this.mySearchListener,
                                                     descriptor -> new NewListPluginComponent(myPluginModel, descriptor, false));

        myInstalledPanel.setSelectionListener(selectionListener);
        PluginManagerConfigurableNew.registerCopyProvider(myInstalledPanel);

        PluginLogo.startBatchMode();

        PluginsGroup installing = new PluginsGroup("Installing");
        installing.descriptors.addAll(MyPluginModel.getInstallingPlugins());
        if (!installing.descriptors.isEmpty()) {
          installing.sortByName();
          installing.titleWithCount();
          myInstalledPanel.addGroup(installing);
        }

        PluginsGroup downloaded = new PluginsGroup("Downloaded");
        PluginsGroup bundled = new PluginsGroup("Bundled");

        ApplicationInfoEx appInfo = ApplicationInfoEx.getInstanceEx();
        int bundledEnabled = 0;
        int downloadedEnabled = 0;

        for (IdeaPluginDescriptor descriptor : PluginManagerCore.getPlugins()) {
          if (!appInfo.isEssentialPlugin(descriptor.getPluginId().getIdString())) {
            if (descriptor.isBundled()) {
              bundled.descriptors.add(descriptor);
              if (descriptor.isEnabled()) {
                bundledEnabled++;
              }
            }
            else {
              downloaded.descriptors.add(descriptor);
              if (descriptor.isEnabled()) {
                downloadedEnabled++;
              }
            }
          }
        }

        if (!downloaded.descriptors.isEmpty()) {
          myUpdateAll.setListener(new LinkListener<Object>() {
            @Override
            public void linkSelected(LinkLabel aSource, Object aLinkData) {
              for (CellPluginComponent plugin : downloaded.ui.plugins) {
                ((NewListPluginComponent)plugin).updatePlugin();
              }
            }
          }, null);
          downloaded.addRightAction(myUpdateAll);

          downloaded.addRightAction(myUpdateCounter);

          downloaded.sortByName();
          downloaded.titleWithCount(downloadedEnabled);
          myInstalledPanel.addGroup(downloaded);
          myPluginModel.addEnabledGroup(downloaded);
        }

        myPluginModel.setDownloadedGroup(myInstalledPanel, downloaded, installing);

        bundled.sortByName();
        bundled.titleWithCount(bundledEnabled);
        myInstalledPanel.addGroup(bundled);
        myPluginModel.addEnabledGroup(bundled);

        myPluginUpdatesService.connectInstalled(updates -> {
          if (ContainerUtil.isEmpty(updates)) {
            for (UIPluginGroup group : myInstalledPanel.getGroups()) {
              for (CellPluginComponent plugin : group.plugins) {
                ((NewListPluginComponent)plugin).setUpdateDescriptor(null);
              }
            }
          }
          else {
            for (PluginDownloader downloader : updates) {
              IdeaPluginDescriptor descriptor = downloader.getDescriptor();
              for (UIPluginGroup group : myInstalledPanel.getGroups()) {
                CellPluginComponent component = group.findComponent(descriptor);
                if (component != null) {
                  ((NewListPluginComponent)component).setUpdateDescriptor(descriptor);
                  break;
                }
              }
            }
          }
          selectionListener.accept(myInstalledPanel);
        });

        PluginLogo.endBatchMode();

        return createScrollPane(myInstalledPanel, true);
      }

      @Override
      protected void updateMainSelection(@NotNull Consumer<PluginsGroupComponent> selectionListener) {
        selectionListener.accept(myInstalledPanel);
      }

      @NotNull
      @Override
      protected SearchResultPanel createSearchPanel(@NotNull Consumer<PluginsGroupComponent> selectionListener,
                                                    @NotNull PluginSearchTextField searchTextField) {
        SearchPopupController installedController = new SearchPopupController(searchTextField) {
          @NotNull
          @Override
          protected List<String> getAttributes() {
            return ContainerUtil.list("#disabled", "#enabled", "#bundled", "#custom", "#inactive", "#invalid", "#outdated", "#uninstalled");
          }

          @Nullable
          @Override
          protected List<String> getValues(@NotNull String attribute) {
            return null;
          }

          @Override
          protected void handleAppendToQuery() {
            showPopupForQuery();
          }

          @Override
          protected void handleAppendAttributeValue() {
            showPopupForQuery();
          }

          @Override
          protected void showPopupForQuery() {
            showSearchPanel(searchTextField.getText());
          }
        };

        PluginsGroupComponent panel = new PluginsGroupComponent(new PluginListLayout(), new MultiSelectionEventHandler(), myNameListener,
                                                                PluginManagerConfigurableNewLayout.this.mySearchListener,
                                                                descriptor -> new NewListPluginComponent(myPluginModel, descriptor,
                                                                                                         false));

        panel.setSelectionListener(selectionListener);
        PluginManagerConfigurableNew.registerCopyProvider(panel);

        myInstalledSearchPanel = new SearchResultPanel(installedController, panel, 0, 0) {
          @Override
          protected void handleQuery(@NotNull String query, @NotNull PluginsGroup result) {
            InstalledPluginsState state = InstalledPluginsState.getInstance();
            SearchQueryParser.Installed parser = new SearchQueryParser.Installed(query);

            for (UIPluginGroup uiGroup : myInstalledPanel.getGroups()) {
              for (CellPluginComponent plugin : uiGroup.plugins) {
                if (parser.attributes) {
                  if (parser.enabled != null && parser.enabled != myPluginModel.isEnabled(plugin.myPlugin)) {
                    continue;
                  }
                  if (parser.bundled != null && parser.bundled != plugin.myPlugin.isBundled()) {
                    continue;
                  }
                  if (parser.invalid != null && parser.invalid != myPluginModel.hasErrors(plugin.myPlugin)) {
                    continue;
                  }
                  if (parser.deleted != null) {
                    if (plugin.myPlugin instanceof IdeaPluginDescriptorImpl) {
                      if (parser.deleted != ((IdeaPluginDescriptorImpl)plugin.myPlugin).isDeleted()) {
                        continue;
                      }
                    }
                    else if (parser.deleted) {
                      continue;
                    }
                  }
                  PluginId pluginId = plugin.myPlugin.getPluginId();
                  if (parser.needUpdate != null && parser.needUpdate != state.hasNewerVersion(pluginId)) {
                    continue;
                  }
                  if (parser.needRestart != null) {
                    if (parser.needRestart != (state.wasInstalled(pluginId) || state.wasUpdated(pluginId))) {
                      continue;
                    }
                  }
                }
                if (parser.searchQuery != null && !StringUtil.containsIgnoreCase(plugin.myPlugin.getName(), parser.searchQuery)) {
                  continue;
                }
                result.descriptors.add(plugin.myPlugin);
              }
            }
          }
        };

        return myInstalledSearchPanel;
      }
    };
  }

  private class ChangePluginStateAction extends AnAction {
    private final boolean myEnable;

    private ChangePluginStateAction(boolean enable) {
      super(enable ? "Enable All Downloaded Plugins" : "Disable All Downloaded Plugins");
      myEnable = enable;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      IdeaPluginDescriptor[] descriptors;
      PluginsGroup group = myPluginModel.getDownloadedGroup();

      if (group == null) {
        ApplicationInfoEx appInfo = ApplicationInfoEx.getInstanceEx();
        List<IdeaPluginDescriptor> descriptorList = new ArrayList<>();

        for (IdeaPluginDescriptor descriptor : PluginManagerCore.getPlugins()) {
          if (!appInfo.isEssentialPlugin(descriptor.getPluginId().getIdString()) &&
              !descriptor.isBundled() && descriptor.isEnabled() != myEnable) {
            descriptorList.add(descriptor);
          }
        }

        descriptors = descriptorList.toArray(new IdeaPluginDescriptor[0]);
      }
      else {
        descriptors = group.ui.plugins.stream().filter(component -> myPluginModel.isEnabled(component.myPlugin) != myEnable)
          .map(component -> component.myPlugin).toArray(IdeaPluginDescriptor[]::new);
      }

      if (descriptors.length > 0) {
        myPluginModel.changeEnableDisable(descriptors, myEnable);
      }
    }
  }

  @NotNull
  private static JComponent createScrollPane(@NotNull PluginsGroupComponent panel, boolean initSelection) {
    JBScrollPane pane =
      new JBScrollPane(panel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    pane.setBorder(JBUI.Borders.empty());
    if (initSelection) {
      panel.initialSelection();
    }
    return pane;
  }

  @NotNull
  private List<IdeaPluginDescriptor> getPluginRepositories() {
    synchronized (myRepositoriesLock) {
      if (myAllRepositoriesList != null) {
        return myAllRepositoriesList;
      }
    }
    try {
      List<IdeaPluginDescriptor> list = RepositoryHelper.loadCachedPlugins();
      if (list != null) {
        return list;
      }
    }
    catch (IOException e) {
      PluginManagerMain.LOG.info(e);
    }
    return Collections.emptyList();
  }

  @NotNull
  private Pair<Map<String, IdeaPluginDescriptor>, Map<String, List<IdeaPluginDescriptor>>> loadPluginRepositories() {
    synchronized (myRepositoriesLock) {
      if (myAllRepositoriesMap != null) {
        return Pair.create(myAllRepositoriesMap, myCustomRepositoriesMap);
      }
    }

    List<IdeaPluginDescriptor> list = new ArrayList<>();
    Map<String, IdeaPluginDescriptor> map = new HashMap<>();
    Map<String, List<IdeaPluginDescriptor>> custom = new HashMap<>();

    for (String host : RepositoryHelper.getPluginHosts()) {
      try {
        List<IdeaPluginDescriptor> descriptors = RepositoryHelper.loadPlugins(host, null);
        if (host != null) {
          custom.put(host, descriptors);
        }
        for (IdeaPluginDescriptor plugin : descriptors) {
          String id = plugin.getPluginId().getIdString();
          if (!map.containsKey(id)) {
            list.add(plugin);
            map.put(id, plugin);
          }
        }
      }
      catch (IOException e) {
        if (host == null) {
          PluginManagerMain.LOG
            .info("Main plugin repository is not available ('" + e.getMessage() + "'). Please check your network settings.");
        }
        else {
          PluginManagerMain.LOG.info(host, e);
        }
      }
    }

    ApplicationManager.getApplication().invokeLater(() -> {
      InstalledPluginsState state = InstalledPluginsState.getInstance();
      for (IdeaPluginDescriptor descriptor : list) {
        state.onDescriptorDownload(descriptor);
      }
    });

    synchronized (myRepositoriesLock) {
      if (myAllRepositoriesList == null) {
        myAllRepositoriesList = list;
        myAllRepositoriesMap = map;
        myCustomRepositoriesMap = custom;
      }
      return Pair.create(myAllRepositoriesMap, myCustomRepositoriesMap);
    }
  }

  private void addGroup(@NotNull List<PluginsGroup> groups,
                        @NotNull String name,
                        @NotNull String showAllQuery,
                        @NotNull ThrowableNotNullFunction<List<IdeaPluginDescriptor>, Boolean, IOException> function) throws IOException {
    PluginsGroup group = new PluginsGroup(name);

    if (Boolean.TRUE.equals(function.fun(group.descriptors))) {
      //noinspection unchecked
      group.rightAction = new LinkLabel("Show All", null, myMarketplaceTab.mySearchListener, showAllQuery);
      group.rightAction.setBorder(JBUI.Borders.emptyRight(5));
    }

    if (!group.descriptors.isEmpty()) {
      groups.add(group);
    }
  }

  private void addGroup(@NotNull List<PluginsGroup> groups,
                        @NotNull Map<String, IdeaPluginDescriptor> allRepositoriesMap,
                        @NotNull String name,
                        @NotNull String query,
                        @NotNull String showAllQuery) throws IOException {
    addGroup(groups, name, showAllQuery, descriptors -> PluginManagerConfigurableNew.loadPlugins(descriptors, allRepositoriesMap, query));
  }

  @Override
  public void disposeUIResources() {
    myPluginModel.toBackground();

    myMarketplaceTab.dispose();
    myInstalledTab.dispose();

    if (myMarketplacePanel != null) {
      myMarketplacePanel.dispose();
    }
    if (myMarketplaceSearchPanel != null) {
      myMarketplaceSearchPanel.dispose();
    }

    myPluginUpdatesService.dispose();

    if (myShutdownCallback != null) {
      myShutdownCallback.run();
      myShutdownCallback = null;
    }
  }

  @Override
  public boolean isModified() {
    if (myPluginModel.needRestart) {
      return true;
    }

    Set<String> disabledPlugins = PluginManagerCore.getDisabledPluginSet();
    int rowCount = myPluginModel.getRowCount();

    for (int i = 0; i < rowCount; i++) {
      IdeaPluginDescriptor descriptor = myPluginModel.getObjectAt(i);
      boolean enabledInTable = myPluginModel.isEnabled(descriptor);

      if (descriptor.isEnabled() != enabledInTable) {
        if (enabledInTable && !disabledPlugins.contains(descriptor.getPluginId().getIdString())) {
          continue; // was disabled automatically on startup
        }
        return true;
      }
    }

    for (Map.Entry<PluginId, Boolean> entry : myPluginModel.getEnabledMap().entrySet()) {
      Boolean enabled = entry.getValue();
      if (enabled != null && !enabled && !disabledPlugins.contains(entry.getKey().getIdString())) {
        return true;
      }
    }

    return false;
  }

  @Override
  public void apply() throws ConfigurationException {
    Map<PluginId, Boolean> enabledMap = myPluginModel.getEnabledMap();
    List<String> dependencies = new ArrayList<>();

    for (Map.Entry<PluginId, Set<PluginId>> entry : myPluginModel.getDependentToRequiredListMap().entrySet()) {
      PluginId id = entry.getKey();

      if (enabledMap.get(id) == null) {
        continue;
      }

      for (PluginId dependId : entry.getValue()) {
        if (!PluginManagerCore.isModuleDependency(dependId)) {
          IdeaPluginDescriptor descriptor = PluginManager.getPlugin(id);
          if (!(descriptor instanceof IdeaPluginDescriptorImpl) || !((IdeaPluginDescriptorImpl)descriptor).isDeleted()) {
            dependencies.add("\"" + (descriptor == null ? id.getIdString() : descriptor.getName()) + "\"");
          }
          break;
        }
      }
    }

    if (!dependencies.isEmpty()) {
      throw new ConfigurationException("<html><body style=\"padding: 5px;\">Unable to apply changes: plugin" +
                                       (dependencies.size() == 1 ? " " : "s ") +
                                       StringUtil.join(dependencies, ", ") +
                                       " won't be able to load.</body></html>");
    }

    int rowCount = myPluginModel.getRowCount();
    for (int i = 0; i < rowCount; i++) {
      IdeaPluginDescriptor descriptor = myPluginModel.getObjectAt(i);
      descriptor.setEnabled(myPluginModel.isEnabled(descriptor.getPluginId()));
    }

    List<String> disableIds = new ArrayList<>();
    for (Map.Entry<PluginId, Boolean> entry : enabledMap.entrySet()) {
      Boolean enabled = entry.getValue();
      if (enabled != null && !enabled) {
        disableIds.add(entry.getKey().getIdString());
      }
    }

    try {
      PluginManagerCore.saveDisabledPlugins(disableIds, false);
    }
    catch (IOException e) {
      PluginManagerMain.LOG.error(e);
    }

    if (myShutdownCallback == null && myPluginModel.createShutdownCallback) {
      myShutdownCallback = () -> ApplicationManager.getApplication().invokeLater(
        () -> PluginManagerConfigurable.shutdownOrRestartApp(IdeBundle.message("update.notifications.title")));
    }
  }

  @NotNull
  @Override
  public MyPluginModel getPluginModel() {
    return myPluginModel;
  }

  @Override
  public void select(@NotNull IdeaPluginDescriptor... descriptors) {
    if (myTabHeaderComponent.getSelectionTab() != INSTALLED_TAB) {
      myTabHeaderComponent.setSelectionWithEvents(INSTALLED_TAB);
    }

    if (descriptors.length == 0) {
      return;
    }

    List<CellPluginComponent> components = new ArrayList<>();

    for (IdeaPluginDescriptor descriptor : descriptors) {
      for (UIPluginGroup group : myInstalledPanel.getGroups()) {
        CellPluginComponent component = group.findComponent(descriptor);
        if (component != null) {
          components.add(component);
          break;
        }
      }
    }

    if (!components.isEmpty()) {
      myInstalledPanel.setSelection(components);
    }
  }

  @Nullable
  @Override
  public Runnable enableSearch(String option) {
    if (StringUtil.isEmpty(option) &&
        (myTabHeaderComponent.getSelectionTab() == MARKETPLACE_TAB ? myMarketplaceSearchPanel : myInstalledSearchPanel).isEmpty()) {
      return null;
    }

    return () -> {
      if (myTabHeaderComponent.getSelectionTab() != INSTALLED_TAB) {
        myTabHeaderComponent.setSelectionWithEvents(INSTALLED_TAB);
      }

      myInstalledTab.clearSearchPanel(option);

      if (!StringUtil.isEmpty(option)) {
        myInstalledTab.showSearchPanel(option);
      }
    };
  }
}