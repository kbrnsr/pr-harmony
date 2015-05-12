package com.monitorjbl.plugins.config;

import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.repository.RepositoryService;
import com.atlassian.stash.scm.CommandOutputHandler;
import com.atlassian.stash.scm.git.GitCommand;
import com.atlassian.stash.scm.git.GitCommandBuilderFactory;
import com.atlassian.stash.user.UserService;
import com.atlassian.utils.process.ProcessException;
import com.atlassian.utils.process.StringOutputHandler;
import com.atlassian.utils.process.Watchdog;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Lists.newArrayList;

public class ConfigDao {
  public static final String REQUIRED_REVIEWS = "requiredReviews";
  public static final String REQUIRED_REVIWERS = "requiredReviewers";
  public static final String DEFAULT_REVIEWERS = "defaultReviewers";
  public static final String EXCLUDED_USERS = "excludedUsers";
  public static final String BLOCKED_COMMITS = "blockedCommits";
  public static final String BLOCKED_PRS = "blockedPRs";
  public static final String AUTOMERGE_PRS = "automergePRs";

  private final PluginSettingsFactory pluginSettingsFactory;
  private final RepositoryService repoService;
  private final GitCommandBuilderFactory commandBuilderFactory;
  private final UserService userService;

  public ConfigDao(PluginSettingsFactory pluginSettingsFactory, RepositoryService repoService,
                   GitCommandBuilderFactory commandBuilderFactory, UserService userService) {
    this.pluginSettingsFactory = pluginSettingsFactory;
    this.repoService = repoService;
    this.commandBuilderFactory = commandBuilderFactory;
    this.userService = userService;
  }

  public Config getConfigForRepo(String projectKey, String repoSlug) {
    PluginSettings settings = settings(projectKey, repoSlug);
    return Config.builder()
        .requiredReviews(Integer.parseInt(get(settings, REQUIRED_REVIEWS, "0")))
        .requiredReviewers(split(get(settings, REQUIRED_REVIWERS, "")))
        .defaultReviewers(split(get(settings, DEFAULT_REVIEWERS, "")))
        .excludedUsers(split(get(settings, EXCLUDED_USERS, "")))
        .blockedCommits(split(get(settings, BLOCKED_COMMITS, "")))
        .blockedPRs(split(get(settings, BLOCKED_PRS, "")))
        .automergePRs(split(get(settings, AUTOMERGE_PRS, "")))
        .build();
  }

  public void setConfigForRepo(String projectKey, String repoSlug, Config config) {
    Predicate<String> branchesFilter = new FilterInvalidBranches(getBranches(projectKey, repoSlug));
    PluginSettings settings = settings(projectKey, repoSlug);
    settings.put(REQUIRED_REVIEWS, Integer.toString(config.getRequiredReviews()));
    settings.put(REQUIRED_REVIWERS, join(config.getRequiredReviewers(), new FilterInvalidUsers()));
    settings.put(DEFAULT_REVIEWERS, join(config.getDefaultReviewers(), new FilterInvalidUsers()));
    settings.put(EXCLUDED_USERS, join(config.getExcludedUsers(), new FilterInvalidUsers()));
    settings.put(BLOCKED_COMMITS, join(config.getBlockedCommits(), branchesFilter));
    settings.put(BLOCKED_PRS, join(config.getBlockedPRs(), branchesFilter));
    settings.put(AUTOMERGE_PRS, join(config.getAutomergePRs(), branchesFilter));
  }

  PluginSettings settings(String projectKey, String repoSlug) {
    return pluginSettingsFactory.createSettingsForKey(projectKey + "-" + repoSlug);
  }

  String get(PluginSettings settings, String key, String defaultValue) {
    String val = (String) settings.get(key);
    return val == null ? defaultValue : val;
  }

  String join(Iterable<String> values, Predicate<String> predicate) {
    return Joiner.on(',').join(filter(values, predicate));
  }

  List<String> split(String value) {
    if ("".equals(value)) {
      return newArrayList();
    } else {
      return newArrayList(Splitter.on(", ").trimResults().split(value));
    }
  }

  List<String> getBranches(String projectKey, String repoSlug) {
    Repository repo = repoService.getBySlug(projectKey, repoSlug);
    GitCommand<String> command = commandBuilderFactory.builder(repo).forEachRef().format("").build(new GitCommandHandler());
    String raw = command.call();

    List<String> branches = newArrayList();
    for (String line : raw.split("\n")) {
      String[] row = line.split("\t");
      if (row[1].startsWith("refs/heads/")) {
        branches.add(row[1].replace("refs/heads/", ""));
      }
    }

    return branches;
  }

  class FilterInvalidUsers implements Predicate<String> {
    @Override
    public boolean apply(String username) {
      return userService.getUserBySlug(username.trim()) != null;
    }
  }

  class FilterInvalidBranches implements Predicate<String> {
    private final List<String> branches;

    public FilterInvalidBranches(List<String> branches) {
      this.branches = branches;
    }

    @Override
    public boolean apply(String refspec) {
      return branches.contains(refspec);
    }
  }

  static class GitCommandHandler implements CommandOutputHandler<String> {
    private final StringOutputHandler outputHandler = new StringOutputHandler();

    @Override
    public String getOutput() {
      String output = outputHandler.getOutput();
      if (output != null && output.trim().isEmpty()) {
        output = null;
      }
      return output;
    }

    @Override
    public void process(final InputStream output) throws ProcessException {
      outputHandler.process(output);
    }

    @Override
    public void complete() throws ProcessException {
      outputHandler.complete();
    }

    @Override
    public void setWatchdog(final Watchdog watchdog) {
      outputHandler.setWatchdog(watchdog);
    }
  }

}