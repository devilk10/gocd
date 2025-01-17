/*
 * Copyright 2017 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.domain.materials.git;

import com.thoughtworks.go.config.materials.git.GitMaterialConfig;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.domain.materials.ModifiedAction;
import com.thoughtworks.go.domain.materials.ModifiedFile;
import com.thoughtworks.go.domain.materials.TestSubprocessExecutionContext;
import com.thoughtworks.go.domain.materials.mercurial.StringRevision;
import com.thoughtworks.go.helper.GitSubmoduleRepos;
import com.thoughtworks.go.helper.TestRepo;
import com.thoughtworks.go.mail.SysOutStreamConsumer;
import com.thoughtworks.go.util.DateUtils;
import com.thoughtworks.go.util.command.CommandLine;
import com.thoughtworks.go.util.command.CommandLineException;
import com.thoughtworks.go.util.command.InMemoryStreamConsumer;
import com.thoughtworks.go.util.command.UrlArgument;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.RestoreSystemProperties;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.mockito.Mock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.thoughtworks.go.domain.materials.git.GitTestRepo.*;
import static com.thoughtworks.go.util.DateUtils.parseRFC822;
import static com.thoughtworks.go.util.ReflectionUtil.getField;
import static com.thoughtworks.go.util.command.ProcessOutputStreamConsumer.inMemoryConsumer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.io.filefilter.FileFilterUtils.*;
import static org.apache.commons.lang3.time.DateUtils.addDays;
import static org.apache.commons.lang3.time.DateUtils.setMilliseconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.MockitoAnnotations.initMocks;

public class GitCommandTest {
    private static final String BRANCH = "foo";
    private static final String SUBMODULE = "submodule-1";

    private GitCommand git;
    private String repoUrl;
    private File repoLocation;
    private static final Date THREE_DAYS_FROM_NOW = setMilliseconds(addDays(new Date(), 3), 0);
    private GitTestRepo gitRepo;
    private File gitLocalRepoDir;
    private GitTestRepo gitFooBranchBundle;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Rule
    public final TestRule restoreSystemProperties = new RestoreSystemProperties();

    @Mock
    private TestSubprocessExecutionContext testSubprocessExecutionContext;

    @Rule
    public final ExpectedException expectedException = ExpectedException.none();

    @Before
    public void setup() throws Exception {
        gitRepo = new GitTestRepo(temporaryFolder);
        gitLocalRepoDir = createTempWorkingDirectory();
        git = new GitCommand(null, gitLocalRepoDir, GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        repoLocation = gitRepo.gitRepository();
        repoUrl = gitRepo.projectRepositoryUrl();
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        int returnCode = git.cloneWithNoCheckout(outputStreamConsumer, repoUrl);
        if (returnCode > 0) {
            fail(outputStreamConsumer.getAllOutput());
        }
        gitFooBranchBundle = GitTestRepo.testRepoAtBranch(GIT_FOO_BRANCH_BUNDLE, BRANCH, temporaryFolder);
        initMocks(this);
    }

    @After
    public void teardown() throws Exception {
        unsetColoring();
        TestRepo.internalTearDown();
    }

    @Test
    public void shouldDefaultToMasterIfNoBranchIsSpecified() {
        assertThat(getField(new GitCommand(null, gitLocalRepoDir, null, false, new HashMap<>(), null), "branch")).isEqualTo("master");
        assertThat(getField(new GitCommand(null, gitLocalRepoDir, " ", false, new HashMap<>(), null), "branch")).isEqualTo("master");
        assertThat(getField(new GitCommand(null, gitLocalRepoDir, "master", false, new HashMap<>(), null), "branch")).isEqualTo("master");
        assertThat(getField(new GitCommand(null, gitLocalRepoDir, "branch", false, new HashMap<>(), null), "branch")).isEqualTo("branch");
    }

    @Test
    public void shouldCloneFromMasterWhenNoBranchIsSpecified() {
        InMemoryStreamConsumer output = inMemoryConsumer();
        git.clone(output, repoUrl);
        CommandLine commandLine = CommandLine.createCommandLine("git").withEncoding("UTF-8").withArg("branch").withWorkingDir(gitLocalRepoDir);
        commandLine.run(output, "");
        assertThat(output.getStdOut()).isEqualTo("* master");
    }

    @Test
    public void freshCloneDoesNotHaveWorkingCopy() {
        assertWorkingCopyNotCheckedOut();
    }

    @Test
    public void freshCloneOnAgentSideShouldHaveWorkingCopyCheckedOut() throws IOException {
        InMemoryStreamConsumer output = inMemoryConsumer();
        File workingDir = createTempWorkingDirectory();
        GitCommand git = new GitCommand(null, workingDir, GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);

        git.clone(output, repoUrl);

        assertWorkingCopyCheckedOut(workingDir);
    }

    @Test
    public void fullCloneIsNotShallow() {
        assertThat(git.isShallow()).isFalse();
    }

    @Test
    public void shouldOnlyCloneLimitedRevisionsIfDepthSpecified() throws Exception {
        FileUtils.deleteQuietly(this.gitLocalRepoDir);
        git.clone(inMemoryConsumer(), repoUrl, 2);
        assertThat(git.isShallow()).isTrue();
        assertThat(git.containsRevisionInBranch(GitTestRepo.REVISION_4)).isTrue();
        assertThat(git.containsRevisionInBranch(GitTestRepo.REVISION_3)).isTrue();
        // can not assert on revision_2, because on old version of git (1.7)
        // depth '2' actually clone 3 revisions
        assertThat(git.containsRevisionInBranch(GitTestRepo.REVISION_1)).isFalse();
        assertThat(git.containsRevisionInBranch(GitTestRepo.REVISION_0)).isFalse();

    }

    @Test
    public void unshallowALocalRepoWithArbitraryDepth() throws Exception {
        FileUtils.deleteQuietly(this.gitLocalRepoDir);
        git.clone(inMemoryConsumer(), repoUrl, 2);
        git.unshallow(inMemoryConsumer(), 3);
        assertThat(git.isShallow()).isTrue();
        assertThat(git.containsRevisionInBranch(GitTestRepo.REVISION_2)).isTrue();
        // can not assert on revision_1, because on old version of git (1.7)
        // depth '3' actually clone 4 revisions
        assertThat(git.containsRevisionInBranch(GitTestRepo.REVISION_0)).isFalse();

        git.unshallow(inMemoryConsumer(), Integer.MAX_VALUE);
        assertThat(git.isShallow()).isFalse();

        assertThat(git.containsRevisionInBranch(GitTestRepo.REVISION_0)).isTrue();
    }

    @Test
    public void unshallowShouldNotResultInWorkingCopyCheckout() {
        FileUtils.deleteQuietly(this.gitLocalRepoDir);
        git.cloneWithNoCheckout(inMemoryConsumer(), repoUrl);
        git.unshallow(inMemoryConsumer(), 3);
        assertWorkingCopyNotCheckedOut();
    }

    @Test
    public void shouldCloneFromBranchWhenMaterialPointsToABranch() throws IOException {
        gitLocalRepoDir = createTempWorkingDirectory();
        git = new GitCommand(null, gitLocalRepoDir, BRANCH, false, new HashMap<>(), null);
        GitCommand branchedGit = new GitCommand(null, gitLocalRepoDir, BRANCH, false, new HashMap<>(), null);
        branchedGit.clone(inMemoryConsumer(), gitFooBranchBundle.projectRepositoryUrl());
        InMemoryStreamConsumer output = inMemoryConsumer();
        CommandLine.createCommandLine("git").withEncoding("UTF-8").withArg("branch").withWorkingDir(gitLocalRepoDir).run(output, "");
        assertThat(output.getStdOut()).isEqualTo("* foo");
    }

    @Test
    public void shouldGetTheCurrentBranchForTheCheckedOutRepo() throws IOException {
        gitLocalRepoDir = createTempWorkingDirectory();
        CommandLine gitCloneCommand = CommandLine.createCommandLine("git").withEncoding("UTF-8").withArg("clone");
        gitCloneCommand.withArg("--branch=" + BRANCH).withArg(new UrlArgument(gitFooBranchBundle.projectRepositoryUrl())).withArg(gitLocalRepoDir.getAbsolutePath());
        gitCloneCommand.run(inMemoryConsumer(), "");
        git = new GitCommand(null, gitLocalRepoDir, BRANCH, false, new HashMap<>(), null);
        assertThat(git.getCurrentBranch()).isEqualTo(BRANCH);
    }

    @Test
    public void shouldBombForFetchFailure() throws IOException {
        executeOnGitRepo("git", "remote", "rm", "origin");
        executeOnGitRepo("git", "remote", "add", "origin", "git://user:secret@foo.bar/baz");
        try {
            InMemoryStreamConsumer output = new InMemoryStreamConsumer();
            git.fetch(output);
            fail("should have failed for non 0 return code. Git output was:\n " + output.getAllOutput());
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo("git fetch failed for [git://user:******@foo.bar/baz]");
        }
    }

    @Test
    public void shouldBombForResettingFailure() throws IOException {
        try {
            git.resetWorkingDir(new SysOutStreamConsumer(), new StringRevision("abcdef"));
            fail("should have failed for non 0 return code");
        } catch (Exception e) {
            assertThat(e.getMessage()).isEqualTo(String.format("git reset failed for [%s]", gitLocalRepoDir));
        }
    }

    @Test
    public void shouldOutputSubmoduleRevisionsAfterUpdate() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos(temporaryFolder);
        submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        gitWithSubmodule.clone(inMemoryConsumer(), submoduleRepos.mainRepo().getUrl());
        InMemoryStreamConsumer outConsumer = new InMemoryStreamConsumer();
        gitWithSubmodule.resetWorkingDir(outConsumer, new StringRevision("HEAD"));
        Matcher matcher = Pattern.compile(".*^\\s[a-f0-9A-F]{40} sub1 \\(heads/master\\)$.*", Pattern.MULTILINE | Pattern.DOTALL).matcher(outConsumer.getAllOutput());
        assertThat(matcher.matches()).isTrue();
    }

    @Test
    public void shouldBombForResetWorkingDirWhenSubmoduleUpdateFails() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos(temporaryFolder);
        File submoduleFolder = submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        gitWithSubmodule.clone(inMemoryConsumer(), submoduleRepos.mainRepo().getUrl());
        FileUtils.deleteDirectory(submoduleFolder);

        assertThat(submoduleFolder.exists()).isFalse();
        try {
            gitWithSubmodule.resetWorkingDir(new SysOutStreamConsumer(), new StringRevision("HEAD"));
            fail("should have failed for non 0 return code");
        } catch (Exception e) {
            assertThat(e.getMessage()).containsPattern(
                    String.format("[Cc]lone of '%s' into submodule path '((.*)[\\/])?sub1' failed", Pattern.quote(submoduleFolder.getAbsolutePath())));
        }
    }

    @Test
    public void shouldRetrieveLatestModification() throws Exception {
        Modification mod = git.latestModification().get(0);
        assertThat(mod.getUserName()).isEqualTo("Chris Turner <cturner@thoughtworks.com>");
        assertThat(mod.getComment()).isEqualTo("Added 'run-till-file-exists' ant target");
        assertThat(mod.getModifiedTime()).isEqualTo(parseRFC822("Fri, 12 Feb 2010 16:12:04 -0800"));
        assertThat(mod.getRevision()).isEqualTo("5def073a425dfe239aabd4bf8039ffe3b0e8856b");

        List<ModifiedFile> files = mod.getModifiedFiles();
        assertThat(files.size()).isEqualTo(1);
        assertThat(files.get(0).getFileName()).isEqualTo("build.xml");
        assertThat(files.get(0).getAction()).isEqualTo(ModifiedAction.modified);
    }

    @Test
    public void shouldRetrieveLatestModificationWhenColoringIsSetToAlways() throws Exception {
        setColoring();
        Modification mod = git.latestModification().get(0);
        assertThat(mod.getUserName()).isEqualTo("Chris Turner <cturner@thoughtworks.com>");
        assertThat(mod.getComment()).isEqualTo("Added 'run-till-file-exists' ant target");
        assertThat(mod.getModifiedTime()).isEqualTo(parseRFC822("Fri, 12 Feb 2010 16:12:04 -0800"));
        assertThat(mod.getRevision()).isEqualTo("5def073a425dfe239aabd4bf8039ffe3b0e8856b");

        List<ModifiedFile> files = mod.getModifiedFiles();
        assertThat(files.size()).isEqualTo(1);
        assertThat(files.get(0).getFileName()).isEqualTo("build.xml");
        assertThat(files.get(0).getAction()).isEqualTo(ModifiedAction.modified);
    }

    @Test
    public void retrieveLatestModificationShouldNotResultInWorkingCopyCheckOut() throws Exception {
        git.latestModification();
        assertWorkingCopyNotCheckedOut();
    }

    @Test
    public void getModificationsSinceShouldNotResultInWorkingCopyCheckOut() throws Exception {
        git.modificationsSince(GitTestRepo.REVISION_2);
        assertWorkingCopyNotCheckedOut();
    }

    @Test
    public void shouldReturnNothingForModificationsSinceIfARebasedCommitSHAIsPassed() throws IOException {
        GitTestRepo remoteRepo = new GitTestRepo(temporaryFolder);
        executeOnGitRepo("git", "remote", "rm", "origin");
        executeOnGitRepo("git", "remote", "add", "origin", remoteRepo.projectRepositoryUrl());
        GitCommand command = new GitCommand(remoteRepo.createMaterial().getFingerprint(), gitLocalRepoDir, "master", false, new HashMap<>(), null);

        Modification modification = remoteRepo.addFileAndAmend("foo", "amendedCommit").get(0);

        assertThat(command.modificationsSince(new StringRevision(modification.getRevision()))).isEmpty();

    }

    @Test
    public void shouldReturnTheRebasedCommitForModificationsSinceTheRevisionBeforeRebase() throws IOException {
        GitTestRepo remoteRepo = new GitTestRepo(temporaryFolder);
        executeOnGitRepo("git", "remote", "rm", "origin");
        executeOnGitRepo("git", "remote", "add", "origin", remoteRepo.projectRepositoryUrl());
        GitCommand command = new GitCommand(remoteRepo.createMaterial().getFingerprint(), gitLocalRepoDir, "master", false, new HashMap<>(), null);

        Modification modification = remoteRepo.addFileAndAmend("foo", "amendedCommit").get(0);

        assertThat(command.modificationsSince(REVISION_4).get(0)).isEqualTo(modification);
    }

    @Test
    public void shouldReturnTheRebasedCommitForModificationsSinceTheRevisionBeforeRebaseWithColoringIsSetToAlways() throws IOException {
        GitTestRepo remoteRepo = new GitTestRepo(temporaryFolder);
        executeOnGitRepo("git", "remote", "rm", "origin");
        executeOnGitRepo("git", "remote", "add", "origin", remoteRepo.projectRepositoryUrl());
        GitCommand command = new GitCommand(remoteRepo.createMaterial().getFingerprint(), gitLocalRepoDir, "master", false, new HashMap<>(), null);

        Modification modification = remoteRepo.addFileAndAmend("foo", "amendedCommit").get(0);
        setColoring();

        assertThat(command.modificationsSince(REVISION_4).get(0)).isEqualTo(modification);
    }

    @Test(expected = CommandLineException.class)
    public void shouldBombIfCheckedForModificationsSinceWithASHAThatNoLongerExists() throws IOException {
        GitTestRepo remoteRepo = new GitTestRepo(temporaryFolder);
        executeOnGitRepo("git", "remote", "rm", "origin");
        executeOnGitRepo("git", "remote", "add", "origin", remoteRepo.projectRepositoryUrl());
        GitCommand command = new GitCommand(remoteRepo.createMaterial().getFingerprint(), gitLocalRepoDir, "master", false, new HashMap<>(), null);

        Modification modification = remoteRepo.checkInOneFile("foo", "Adding a commit").get(0);
        remoteRepo.addFileAndAmend("bar", "amendedCommit");

        command.modificationsSince(new StringRevision(modification.getRevision()));
    }

    @Test(expected = CommandLineException.class)
    public void shouldBombIfCheckedForModificationsSinceWithANonExistentRef() throws IOException {
        GitTestRepo remoteRepo = new GitTestRepo(temporaryFolder);
        executeOnGitRepo("git", "remote", "rm", "origin");
        executeOnGitRepo("git", "remote", "add", "origin", remoteRepo.projectRepositoryUrl());
        GitCommand command = new GitCommand(remoteRepo.createMaterial().getFingerprint(), gitLocalRepoDir, "non-existent-branch", false, new HashMap<>(), null);

        Modification modification = remoteRepo.checkInOneFile("foo", "Adding a commit").get(0);

        command.modificationsSince(new StringRevision(modification.getRevision()));
    }

    @Test
    public void shouldBombWhileRetrievingLatestModificationFromANonExistentRef() throws IOException {
        expectedException.expect(CommandLineException.class);
        expectedException.expectMessage("ambiguous argument 'origin/non-existent-branch': unknown revision or path not in the working tree.");
        GitTestRepo remoteRepo = new GitTestRepo(temporaryFolder);
        executeOnGitRepo("git", "remote", "rm", "origin");
        executeOnGitRepo("git", "remote", "add", "origin", remoteRepo.projectRepositoryUrl());
        GitCommand command = new GitCommand(remoteRepo.createMaterial().getFingerprint(), gitLocalRepoDir, "non-existent-branch", false, new HashMap<>(), null);

        command.latestModification();
    }

    @Test
    public void shouldReturnTrueIfTheGivenBranchContainsTheRevision() {
        assertThat(git.containsRevisionInBranch(REVISION_4)).isTrue();
    }

    @Test
    public void shouldReturnFalseIfTheGivenBranchDoesNotContainTheRevision() {
        assertThat(git.containsRevisionInBranch(NON_EXISTENT_REVISION)).isFalse();
    }

    @Test
    public void shouldRetrieveFilenameForInitialRevision() throws IOException {
        GitTestRepo testRepo = new GitTestRepo(GitTestRepo.GIT_SUBMODULE_REF_BUNDLE, temporaryFolder);
        GitCommand gitCommand = new GitCommand(null, testRepo.gitRepository(), GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        Modification modification = gitCommand.latestModification().get(0);
        assertThat(modification.getModifiedFiles()).hasSize(1);
        assertThat(modification.getModifiedFiles().get(0).getFileName()).isEqualTo("remote.txt");
    }

    @Test
    public void shouldRetrieveLatestModificationFromBranch() throws Exception {
        GitTestRepo branchedRepo = GitTestRepo.testRepoAtBranch(GIT_FOO_BRANCH_BUNDLE, BRANCH, temporaryFolder);
        GitCommand branchedGit = new GitCommand(null, createTempWorkingDirectory(), BRANCH, false, new HashMap<>(), null);
        branchedGit.clone(inMemoryConsumer(), branchedRepo.projectRepositoryUrl());

        Modification mod = branchedGit.latestModification().get(0);

        assertThat(mod.getUserName()).isEqualTo("Chris Turner <cturner@thoughtworks.com>");
        assertThat(mod.getComment()).isEqualTo("Started foo branch");
        assertThat(mod.getModifiedTime()).isEqualTo(parseRFC822("Tue, 05 Feb 2009 14:28:08 -0800"));
        assertThat(mod.getRevision()).isEqualTo("b4fa7271c3cef91822f7fa502b999b2eab2a380d");

        List<ModifiedFile> files = mod.getModifiedFiles();
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName()).isEqualTo("first.txt");
        assertThat(files.get(0).getAction()).isEqualTo(ModifiedAction.modified);
    }

    @Test
    public void shouldRetrieveListOfSubmoduleFolders() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos(temporaryFolder);
        submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        gitWithSubmodule.clone(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());
        gitWithSubmodule.fetchAndResetToHead(outputStreamConsumer);
        gitWithSubmodule.updateSubmoduleWithInit(outputStreamConsumer);
        List<String> folders = gitWithSubmodule.submoduleFolders();
        assertThat(folders).hasSize(1);
        assertThat(folders.get(0)).isEqualTo("sub1");
    }

    @Test
    public void shouldNotThrowErrorWhenConfigRemoveSectionFails() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos(temporaryFolder);
        submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null) {
            //hack to reproduce synchronization issue
            @Override
            public Map<String, String> submoduleUrls() {
                return Collections.singletonMap("submodule", "submodule");
            }
        };
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        gitWithSubmodule.clone(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());

        gitWithSubmodule.updateSubmoduleWithInit(outputStreamConsumer);

    }

    @Test
    public void shouldNotFailIfUnableToRemoveSubmoduleEntryFromConfig() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos(temporaryFolder);
        submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        gitWithSubmodule.clone(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());
        gitWithSubmodule.fetchAndResetToHead(outputStreamConsumer);
        gitWithSubmodule.updateSubmoduleWithInit(outputStreamConsumer);
        List<String> folders = gitWithSubmodule.submoduleFolders();
        assertThat(folders).hasSize(1);
        assertThat(folders.get(0)).isEqualTo("sub1");
    }

    @Test
    public void shouldRetrieveSubmoduleUrls() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos(temporaryFolder);
        submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        gitWithSubmodule.clone(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());
        gitWithSubmodule.fetchAndResetToHead(outputStreamConsumer);

        gitWithSubmodule.updateSubmoduleWithInit(outputStreamConsumer);
        Map<String, String> urls = gitWithSubmodule.submoduleUrls();
        assertThat(urls).hasSize(1);
        assertThat(urls.containsKey("sub1")).isTrue();
        assertThat(urls.get("sub1")).endsWith(SUBMODULE);
    }

    @Test
    public void shouldRetrieveZeroSubmoduleUrlsIfTheyAreNotConfigured() throws Exception {
        Map<String, String> submoduleUrls = git.submoduleUrls();
        assertThat(submoduleUrls).isEmpty();
    }

    @Test
    public void shouldRetrieveRemoteRepoValue() throws Exception {
        assertThat(git.workingRepositoryUrl().forCommandline()).startsWith(repoUrl);
    }

    @Test
    public void shouldCheckIfRemoteRepoExists() throws Exception {
        GitCommand gitCommand = new GitCommand(null, null, null, false, null, null);
        final TestSubprocessExecutionContext executionContext = new TestSubprocessExecutionContext();

        gitCommand.checkConnection(git.workingRepositoryUrl(), "master", executionContext.getDefaultEnvironmentVariables());
    }

    @Test(expected = Exception.class)
    public void shouldThrowExceptionWhenRepoNotExist() throws Exception {
        GitCommand gitCommand = new GitCommand(null, null, null, false, null, null);
        final TestSubprocessExecutionContext executionContext = new TestSubprocessExecutionContext();

        gitCommand.checkConnection(new UrlArgument("git://somewhere.is.not.exist"), "master", executionContext.getDefaultEnvironmentVariables());
    }

    @Test(expected = Exception.class)
    public void shouldThrowExceptionWhenRemoteBranchDoesNotExist() throws Exception {
        GitCommand gitCommand = new GitCommand(null, null, null, false, null, null);

        gitCommand.checkConnection(new UrlArgument(gitRepo.projectRepositoryUrl()), "Invalid_Branch", testSubprocessExecutionContext.getDefaultEnvironmentVariables());
    }


    @Test
    public void shouldIncludeNewChangesInModificationCheck() throws Exception {
        String originalNode = git.latestModification().get(0).getRevision();
        File testingFile = checkInNewRemoteFile();

        Modification modification = git.latestModification().get(0);
        assertThat(modification.getRevision()).isNotEqualTo(originalNode);
        assertThat(modification.getComment()).isEqualTo("New checkin of " + testingFile.getName());
        assertThat(modification.getModifiedFiles()).hasSize(1);
        assertThat(modification.getModifiedFiles().get(0).getFileName()).isEqualTo(testingFile.getName());
    }

    @Test
    public void shouldIncludeChangesFromTheFutureInModificationCheck() throws Exception {
        String originalNode = git.latestModification().get(0).getRevision();
        File testingFile = checkInNewRemoteFileInFuture(THREE_DAYS_FROM_NOW);

        Modification modification = git.latestModification().get(0);
        assertThat(modification.getRevision()).isNotEqualTo(originalNode);
        assertThat(modification.getComment()).isEqualTo("New checkin of " + testingFile.getName());
        assertThat(modification.getModifiedTime()).isEqualTo(THREE_DAYS_FROM_NOW);
    }

    @Test
    public void shouldThrowExceptionIfRepoCanNotConnectWhenModificationCheck() throws Exception {
        FileUtils.deleteQuietly(repoLocation);
        try {
            git.latestModification();
            fail("Should throw exception when repo cannot connected");
        } catch (Exception e) {
            assertThat(e.getMessage()).matches(str -> str.contains("The remote end hung up unexpectedly") ||
                    str.contains("Could not read from remote repository"));
        }
    }

    @Test
    public void shouldParseGitOutputCorrectly() throws IOException {
        List<String> stringList;
        try (InputStream resourceAsStream = getClass().getResourceAsStream("git_sample_output.text")) {
            stringList = IOUtils.readLines(resourceAsStream, UTF_8);
        }

        GitModificationParser parser = new GitModificationParser();
        List<Modification> mods = parser.parse(stringList);
        assertThat(mods).hasSize(3);

        Modification mod = mods.get(2);
        assertThat(mod.getRevision()).isEqualTo("46cceff864c830bbeab0a7aaa31707ae2302762f");
        assertThat(mod.getModifiedTime()).isEqualTo(DateUtils.parseISO8601("2009-08-11 12:37:09 -0700"));
        assertThat(mod.getUserDisplayName()).isEqualTo("Cruise Developer <cruise@cruise-sf3.(none)>");
        assertThat(mod.getComment()).isEqualTo("author:cruise <cceuser@CceDev01.(none)>\n"
                + "node:ecfab84dd4953105e3301c5992528c2d381c1b8a\n"
                + "date:2008-12-31 14:32:40 +0800\n"
                + "description:Moving rakefile to build subdirectory for #2266\n"
                + "\n"
                + "author:CceUser <cceuser@CceDev01.(none)>\n"
                + "node:fd16efeb70fcdbe63338c49995ce9ff7659e6e77\n"
                + "date:2008-12-31 14:17:06 +0800\n"
                + "description:Adding rakefile");
    }

    @Test
    public void shouldCleanUnversionedFilesInsideSubmodulesBeforeUpdating() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos(temporaryFolder);
        String submoduleDirectoryName = "local-submodule";
        submoduleRepos.addSubmodule(SUBMODULE, submoduleDirectoryName);
        File cloneDirectory = createTempWorkingDirectory();
        GitCommand clonedCopy = new GitCommand(null, cloneDirectory, GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        clonedCopy.clone(outputStreamConsumer, submoduleRepos.mainRepo().getUrl()); // Clone repository without submodules
        clonedCopy.resetWorkingDir(outputStreamConsumer, new StringRevision("HEAD"));  // Pull submodules to working copy - Pipeline counter 1
        File unversionedFile = new File(new File(cloneDirectory, submoduleDirectoryName), "unversioned_file.txt");
        FileUtils.writeStringToFile(unversionedFile, "this is an unversioned file. lets see you deleting me.. come on.. I dare you!!!!", UTF_8);

        clonedCopy.resetWorkingDir(outputStreamConsumer, new StringRevision("HEAD")); // Should clean unversioned file on next fetch - Pipeline counter 2

        assertThat(unversionedFile.exists()).isFalse();
    }

    @Test
    public void shouldRemoveChangesToModifiedFilesInsideSubmodulesBeforeUpdating() throws Exception {
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos(temporaryFolder);
        String submoduleDirectoryName = "local-submodule";
        File cloneDirectory = createTempWorkingDirectory();

        File remoteSubmoduleLocation = submoduleRepos.addSubmodule(SUBMODULE, submoduleDirectoryName);

        /* Simulate an agent checkout of code. */
        GitCommand clonedCopy = new GitCommand(null, cloneDirectory, GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        clonedCopy.clone(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());
        clonedCopy.resetWorkingDir(outputStreamConsumer, new StringRevision("HEAD"));

        /* Simulate a local modification of file inside submodule, on agent side. */
        File fileInSubmodule = allFilesIn(new File(cloneDirectory, submoduleDirectoryName), "file-").get(0);
        FileUtils.writeStringToFile(fileInSubmodule, "Some other new content.", UTF_8);

        /* Commit a change to the file on the repo. */
        List<Modification> modifications = submoduleRepos.modifyOneFileInSubmoduleAndUpdateMainRepo(
                remoteSubmoduleLocation, submoduleDirectoryName, fileInSubmodule.getName(), "NEW CONTENT OF FILE");

        /* Simulate start of a new build on agent. */
        clonedCopy.fetch(outputStreamConsumer);
        clonedCopy.resetWorkingDir(outputStreamConsumer, new StringRevision(modifications.get(0).getRevision()));

        assertThat(FileUtils.readFileToString(fileInSubmodule, UTF_8)).isEqualTo("NEW CONTENT OF FILE");
    }

    @Test
    public void shouldAllowSubmoduleUrlsToChange() throws Exception {
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos(temporaryFolder);
        String submoduleDirectoryName = "local-submodule";
        File cloneDirectory = createTempWorkingDirectory();

        File remoteSubmoduleLocation = submoduleRepos.addSubmodule(SUBMODULE, submoduleDirectoryName);

        GitCommand clonedCopy = new GitCommand(null, cloneDirectory, GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        clonedCopy.clone(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());
        clonedCopy.fetchAndResetToHead(outputStreamConsumer);

        submoduleRepos.changeSubmoduleUrl(submoduleDirectoryName);

        clonedCopy.fetchAndResetToHead(outputStreamConsumer);
    }

    @Test
    public void shouldCleanIgnoredFilesIfToggleIsDisabled() throws IOException {
        InMemoryStreamConsumer output = inMemoryConsumer();
        File gitIgnoreFile = new File(repoLocation, ".gitignore");
        FileUtils.writeStringToFile(gitIgnoreFile, "*.foo", Charset.forName("UTF-8"));
        gitRepo.addFileAndPush(gitIgnoreFile, "added gitignore");
        git.fetchAndResetToHead(output);

        File ignoredFile = new File(gitLocalRepoDir, "ignored.foo");
        assertThat(ignoredFile.createNewFile()).isTrue();
        git.fetchAndResetToHead(output);
        assertThat(ignoredFile.exists()).isFalse();
    }

    @Test
    public void shouldNotCleanIgnoredFilesIfToggleIsEnabled() throws IOException {
        System.setProperty("toggle.agent.git.clean.keep.ignored.files", "Y");
        InMemoryStreamConsumer output = inMemoryConsumer();
        File gitIgnoreFile = new File(repoLocation, ".gitignore");
        FileUtils.writeStringToFile(gitIgnoreFile, "*.foo", Charset.forName("UTF-8"));
        gitRepo.addFileAndPush(gitIgnoreFile, "added gitignore");
        git.fetchAndResetToHead(output);

        File ignoredFile = new File(gitLocalRepoDir, "ignored.foo");
        assertThat(ignoredFile.createNewFile()).isTrue();
        git.fetchAndResetToHead(output);
        assertThat(ignoredFile.exists()).isTrue();
    }

    private List<File> allFilesIn(File directory, String prefixOfFiles) {
        return new ArrayList<>(FileUtils.listFiles(directory, andFileFilter(fileFileFilter(), prefixFileFilter(prefixOfFiles)), null));
    }

    private File createTempWorkingDirectory() throws IOException {
        return temporaryFolder.newFolder("GitCommandTest" + System.currentTimeMillis(), "repo");
    }

    private File checkInNewRemoteFile() throws IOException {
        GitCommand remoteGit = new GitCommand(null, repoLocation, GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        File testingFile = new File(repoLocation, "testing-file" + System.currentTimeMillis() + ".txt");
        testingFile.createNewFile();
        remoteGit.add(testingFile);
        remoteGit.commit("New checkin of " + testingFile.getName());
        return testingFile;
    }

    private File checkInNewRemoteFileInFuture(Date checkinDate) throws IOException {
        GitCommand remoteGit = new GitCommand(null, repoLocation, GitMaterialConfig.DEFAULT_BRANCH, false, new HashMap<>(), null);
        File testingFile = new File(repoLocation, "testing-file" + System.currentTimeMillis() + ".txt");
        testingFile.createNewFile();
        remoteGit.add(testingFile);
        remoteGit.commitOnDate("New checkin of " + testingFile.getName(), checkinDate);
        return testingFile;
    }

    private TypeSafeMatcher<String> startsWith(final String repoUrl) {
        return new TypeSafeMatcher<String>() {
            public boolean matchesSafely(String item) {
                return item.startsWith(repoUrl);
            }

            public void describeTo(Description description) {
                description.appendText("to start with \"" + repoUrl + "\"");
            }
        };
    }

    private void executeOnGitRepo(String command, String... args) throws IOException {
        executeOnDir(gitLocalRepoDir, command, args);
    }

    private void executeOnDir(File dir, String command, String... args) {
        CommandLine commandLine = CommandLine.createCommandLine(command);
        commandLine.withArgs(args);
        commandLine.withEncoding("utf-8");
        assertThat(dir.exists()).isTrue();
        commandLine.setWorkingDir(dir);
        commandLine.runOrBomb(true, null);
    }

    private void setColoring() throws IOException {
        executeOnGitRepo("git", "config", "color.diff", "always");
        executeOnGitRepo("git", "config", "color.status", "always");
        executeOnGitRepo("git", "config", "color.interactive", "always");
        executeOnGitRepo("git", "config", "color.branch", "always");
    }

    private void unsetColoring() throws IOException {
        executeOnGitRepo("git", "config", "color.diff", "auto");
        executeOnGitRepo("git", "config", "color.status", "auto");
        executeOnGitRepo("git", "config", "color.interactive", "auto");
        executeOnGitRepo("git", "config", "color.branch", "auto");
    }

    private void assertWorkingCopyNotCheckedOut() {
        assertThat(gitLocalRepoDir.listFiles()).isEqualTo(new File[]{new File(gitLocalRepoDir, ".git")});
    }

    private void assertWorkingCopyCheckedOut(File workingDir) {
        assertThat(workingDir.listFiles().length).isGreaterThan(1);
    }
}
