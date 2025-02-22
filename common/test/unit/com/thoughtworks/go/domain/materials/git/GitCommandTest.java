/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
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
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.domain.materials.git;

import com.googlecode.junit.ext.JunitExtRunner;
import com.thoughtworks.go.config.materials.git.GitMaterialConfig;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.domain.materials.ModifiedAction;
import com.thoughtworks.go.domain.materials.ModifiedFile;
import com.thoughtworks.go.domain.materials.mercurial.StringRevision;
import com.thoughtworks.go.helper.GitSubmoduleRepos;
import com.thoughtworks.go.helper.TestRepo;
import com.thoughtworks.go.mail.SysOutStreamConsumer;
import com.thoughtworks.go.util.DateUtils;
import com.thoughtworks.go.util.FileUtil;
import com.thoughtworks.go.util.ReflectionUtil;
import com.thoughtworks.go.util.TestFileUtil;
import com.thoughtworks.go.util.command.*;
import org.apache.commons.io.FileUtils;
import org.hamcrest.Description;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.core.Is;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.thoughtworks.go.domain.materials.git.GitTestRepo.GIT_FOO_BRANCH_BUNDLE;
import static com.thoughtworks.go.util.DateUtils.parseRFC822;
import static com.thoughtworks.go.util.FileUtil.readLines;
import static com.thoughtworks.go.util.command.ProcessOutputStreamConsumer.inMemoryConsumer;
import static org.apache.commons.io.filefilter.FileFilterUtils.*;
import static org.apache.commons.lang.time.DateUtils.addDays;
import static org.apache.commons.lang.time.DateUtils.setMilliseconds;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.StringContains.containsString;
import static org.hamcrest.core.StringEndsWith.endsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

@RunWith(JunitExtRunner.class)
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

    @Before public void setup() throws Exception {
        gitRepo = new GitTestRepo();
        gitLocalRepoDir = createTempWorkingDirectory();
        git = new GitCommand(null, gitLocalRepoDir, GitMaterialConfig.DEFAULT_BRANCH, false);
        repoLocation = gitRepo.gitRepository();
        repoUrl = gitRepo.projectRepositoryUrl();
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        int returnCode = git.cloneFrom(outputStreamConsumer, repoUrl);
        if (returnCode > 0) {
            fail(outputStreamConsumer.getAllOutput());
        }
        gitFooBranchBundle = GitTestRepo.testRepoAtBranch(GIT_FOO_BRANCH_BUNDLE, BRANCH);
    }

    @After public void teardown() throws Exception {
        TestRepo.internalTearDown();
    }

    @Test
    public void shouldDefaultToMasterIfNoBranchIsSpecified(){
        assertThat((String) ReflectionUtil.getField(new GitCommand(null, gitLocalRepoDir, null, false), "branch"), Is.is("master"));
        assertThat((String) ReflectionUtil.getField(new GitCommand(null, gitLocalRepoDir, " ", false), "branch"), Is.is("master"));
        assertThat((String) ReflectionUtil.getField(new GitCommand(null, gitLocalRepoDir, "master", false), "branch"), Is.is("master"));
        assertThat((String) ReflectionUtil.getField(new GitCommand(null, gitLocalRepoDir, "branch", false), "branch"), Is.is("branch"));
    }

    @Test
    public void shouldCloneFromMasterWhenNoBranchIsSpecified(){
        InMemoryStreamConsumer output = inMemoryConsumer();
        git.cloneFrom(output, repoUrl);
        CommandLine commandLine = CommandLine.createCommandLine("git").withEncoding("UTF-8").withArg("branch").withWorkingDir(gitLocalRepoDir);
        commandLine.run(output, "");
        assertThat(output.getStdOut(), Is.is("* master"));
    }


    @Test
    public void fullCloneIsNotShallow() {
        assertThat(git.isShallow(), is(false));
    }

    @Test
    public void shouldOnlyCloneLimitedRevisionsIfDepthSpecified() throws Exception {
        FileUtil.deleteFolder(this.gitLocalRepoDir);
        git.cloneFrom(inMemoryConsumer(), repoUrl, 2);
        assertThat(git.isShallow(), is(true));
        assertThat(git.hasRevision(GitTestRepo.REVISION_4), is(true));
        assertThat(git.hasRevision(GitTestRepo.REVISION_3), is(true));
        // can not assert on revision_2, because on old version of git (1.7)
        // depth '2' actually clone 3 revisions
        assertThat(git.hasRevision(GitTestRepo.REVISION_1), is(false));
        assertThat(git.hasRevision(GitTestRepo.REVISION_0), is(false));

    }

    @Test
    public void unshallowALocalRepoWithArbitraryDepth() throws Exception {
        FileUtil.deleteFolder(this.gitLocalRepoDir);
        git.cloneFrom(inMemoryConsumer(), repoUrl, 2);
        git.unshallow(inMemoryConsumer(), 3);
        assertThat(git.isShallow(), is(true));
        assertThat(git.hasRevision(GitTestRepo.REVISION_2), is(true));
        // can not assert on revision_1, because on old version of git (1.7)
        // depth '3' actually clone 4 revisions
        assertThat(git.hasRevision(GitTestRepo.REVISION_0), is(false));

        git.unshallow(inMemoryConsumer(), Integer.MAX_VALUE);
        assertThat(git.isShallow(), is(false));

        assertThat(git.hasRevision(GitTestRepo.REVISION_0), is(true));
    }

    @Test
    public void shouldCloneFromBranchWhenMaterialPointsToABranch() throws IOException {
        gitLocalRepoDir = createTempWorkingDirectory();
        git = new GitCommand(null, gitLocalRepoDir, BRANCH, false);
        GitCommand branchedGit = new GitCommand(null, gitLocalRepoDir, BRANCH, false);
        branchedGit.cloneFrom(inMemoryConsumer(), gitFooBranchBundle.projectRepositoryUrl());
        InMemoryStreamConsumer output = inMemoryConsumer();
        CommandLine.createCommandLine("git").withEncoding("UTF-8").withArg("branch").withWorkingDir(gitLocalRepoDir).run(output, "");
        assertThat(output.getStdOut(), Is.is("* foo"));
    }

    @Test
    public void shouldGetTheCurrentBranchForTheCheckedOutRepo(){
        gitLocalRepoDir = createTempWorkingDirectory();
        CommandLine gitCloneCommand = CommandLine.createCommandLine("git").withEncoding("UTF-8").withArg("clone");
        gitCloneCommand.withArg("--branch=" + BRANCH).withArg(new UrlArgument(gitFooBranchBundle.projectRepositoryUrl())).withArg(gitLocalRepoDir.getAbsolutePath());
        gitCloneCommand.run(inMemoryConsumer(), "");
        git = new GitCommand(null, gitLocalRepoDir, BRANCH, false);
        assertThat(git.getCurrentBranch(), Is.is(BRANCH));
    }

    @Test
    public void shouldBombForFetchAndResetFailureWhileFetching() throws IOException {
        executeOnGitRepo("git", "remote", "rm", "origin");
        executeOnGitRepo("git", "remote", "add", "origin", "git://user:secret@foo.bar/baz");
        try {
            InMemoryStreamConsumer output = new InMemoryStreamConsumer();
            git.fetchAndReset(output, new StringRevision("ab9ff2cee965ae4d0778dbcda1fadffbbc202e85"));
            fail("should have failed for non 0 return code. Git output was:\n " + output.getAllOutput());
        } catch (Exception e) {
            assertThat(e.getMessage(), is(String.format("git fetch failed for [git://user:******@foo.bar/baz]")));
        }
    }

    @Test
    public void shouldBombForFetchAndResetFailureWhileReseting() throws IOException {
        try {
            git.fetchAndReset(new SysOutStreamConsumer(), new StringRevision("abcdef"));
            fail("should have failed for non 0 return code");
        } catch (Exception e) {
            assertThat(e.getMessage(), is(String.format("git reset failed for [%s]", gitLocalRepoDir)));
        }
    }

    @Test
    public void shouldOutputSubmoduleRevisionsAfterUpdate() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos();
        submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false);
        gitWithSubmodule.cloneFrom(inMemoryConsumer(), submoduleRepos.mainRepo().getUrl());
        InMemoryStreamConsumer outConsumer = new InMemoryStreamConsumer();
        gitWithSubmodule.fetchAndReset(outConsumer, new StringRevision("HEAD"));
        Matcher matcher = Pattern.compile(".*^\\s[a-f0-9A-F]{40} sub1 \\(heads/master\\)$.*", Pattern.MULTILINE | Pattern.DOTALL).matcher(outConsumer.getAllOutput());
        assertThat(matcher.matches(), is(true));
    }

    @Test
    public void shouldBombForFetchAndResetWhenSubmoduleUpdateFails() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos();
        File submoduleFolder = submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false);
        gitWithSubmodule.cloneFrom(inMemoryConsumer(), submoduleRepos.mainRepo().getUrl());
        FileUtils.deleteDirectory(submoduleFolder);

        assertThat(submoduleFolder.exists(), is(false));
        try {
            gitWithSubmodule.fetchAndReset(new SysOutStreamConsumer(), new StringRevision("HEAD"));
            fail("should have failed for non 0 return code");
        } catch (Exception e) {
            assertThat(e.getMessage(), containsString(String.format("Clone of '%s' into submodule path 'sub1' failed", submoduleFolder.getAbsolutePath())));
        }
    }

    @Test
    public void shouldRetrieveLatestModification() throws Exception {
        Modification mod = git.latestModification();
        assertThat(mod.getUserName(), is("Chris Turner <cturner@thoughtworks.com>"));
        assertThat(mod.getComment(), is("Added 'run-till-file-exists' ant target"));
        assertThat(mod.getModifiedTime(), is(parseRFC822("Fri, 12 Feb 2010 16:12:04 -0800")));
        assertThat(mod.getRevision(), is("5def073a425dfe239aabd4bf8039ffe3b0e8856b"));

        List<ModifiedFile> files = mod.getModifiedFiles();
        assertThat(files.size(), is(1));
        assertThat(files.get(0).getFileName(), is("build.xml"));
        assertThat(files.get(0).getAction(), Matchers.is(ModifiedAction.modified));
    }

    @Test
    public void shouldRetrieveFilenameForInitialRevision() throws IOException {
        GitTestRepo testRepo = new GitTestRepo(GitTestRepo.GIT_SUBMODULE_REF_BUNDLE);
        GitCommand gitCommand = new GitCommand(null, testRepo.gitRepository(), GitMaterialConfig.DEFAULT_BRANCH, false);
        Modification modification = gitCommand.latestModification();
        assertThat(modification.getModifiedFiles().size(), is(1));
        assertThat(modification.getModifiedFiles().get(0).getFileName(), is("remote.txt"));
    }

    @Test public void shouldRetrieveLatestModificationFromBranch() throws Exception {
        GitTestRepo branchedRepo = GitTestRepo.testRepoAtBranch(GIT_FOO_BRANCH_BUNDLE, BRANCH);
        GitCommand branchedGit = new GitCommand(null, createTempWorkingDirectory(), BRANCH, false);
        branchedGit.cloneFrom(inMemoryConsumer(), branchedRepo.projectRepositoryUrl());

        Modification mod = branchedGit.latestModification();

        assertThat(mod.getUserName(), is("Chris Turner <cturner@thoughtworks.com>"));
        assertThat(mod.getComment(), is("Started foo branch"));
        assertThat(mod.getModifiedTime(), is(parseRFC822("Tue, 05 Feb 2009 14:28:08 -0800")));
        assertThat(mod.getRevision(), is("b4fa7271c3cef91822f7fa502b999b2eab2a380d"));

        List<ModifiedFile> files = mod.getModifiedFiles();
        assertThat(files.size(), is(1));
        assertThat(files.get(0).getFileName(), is("first.txt"));
        assertThat(files.get(0).getAction(), is(ModifiedAction.modified));
    }

    @Test
    public void shouldRetrieveListOfSubmoduleFolders() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos();
        submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false);
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        gitWithSubmodule.cloneFrom(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());

        gitWithSubmodule.updateSubmoduleWithInit(outputStreamConsumer);
        List<String> folders = gitWithSubmodule.submoduleFolders();
        assertThat(folders.size(), is(1));
        assertThat(folders.get(0), is("sub1"));
    }

    @Test
    public void shouldNotThrowErrorWhenConfigRemoveSectionFails() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos();
        submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false) {
            //hack to reproduce synchronization issue
            @Override public Map<String, String> submoduleUrls() {
                return Collections.singletonMap("submodule", "submodule");
            }
        };
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        gitWithSubmodule.cloneFrom(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());

        gitWithSubmodule.updateSubmoduleWithInit(outputStreamConsumer);

    }

    @Test
    public void shouldNotFailIfUnableToRemoveSubmoduleEntryFromConfig() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos();
        submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false);
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        gitWithSubmodule.cloneFrom(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());

        gitWithSubmodule.updateSubmoduleWithInit(outputStreamConsumer);
        List<String> folders = gitWithSubmodule.submoduleFolders();
        assertThat(folders.size(), is(1));
        assertThat(folders.get(0), is("sub1"));
    }

    @Test
    public void shouldRetrieveSubmoduleUrls() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos();
        submoduleRepos.addSubmodule(SUBMODULE, "sub1");
        GitCommand gitWithSubmodule = new GitCommand(null, createTempWorkingDirectory(), GitMaterialConfig.DEFAULT_BRANCH, false);
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        gitWithSubmodule.cloneFrom(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());

        gitWithSubmodule.updateSubmoduleWithInit(outputStreamConsumer);
        Map<String, String> urls = gitWithSubmodule.submoduleUrls();
        assertThat(urls.size(), is(1));
        assertThat(urls.containsKey("sub1"), is(true));
        assertThat(urls.get("sub1"), endsWith(SUBMODULE));
    }

    @Test
    public void shouldRetrieveZeroSubmoduleUrlsIfTheyAreNotConfigured() throws Exception {
        Map<String, String> submoduleUrls = git.submoduleUrls();
        assertThat(submoduleUrls.size(), is(0));
    }

    @Test public void shouldRetrieveRemoteRepoValue() throws Exception {
        assertThat(git.workingRepositoryUrl().forCommandline(), startsWith(repoUrl));
    }

    @Test public void shouldCheckIfRemoteRepoExists() throws Exception {
        try {
            GitCommand.checkConnection(git.workingRepositoryUrl());
        } catch (Exception e) {
            fail();
        }
    }

    @Test(expected = Exception.class)
    public void shouldThrowExceptionWhenRepoNotExist() throws Exception {
        GitCommand.checkConnection(new UrlArgument("git://somewhere.is.not.exist"));
    }

    @Test
    public void shouldExecuteGitLsWhenCheckingToSeeIfWeCanConnectToTheRepo() throws Exception {
        UrlArgument url = new UrlArgument("git://github.com/xli/dtr.git");
        CommandLine commandLine = GitCommand.commandToCheckConnection(url);
        assertThat(commandLine.getExecutable(), is("git"));
        List<CommandArgument> arguments = commandLine.getArguments();
        assertThat((StringArgument) arguments.get(0), is(new StringArgument("ls-remote")));
        assertThat((UrlArgument) arguments.get(1), is(url));
    }


    @Test public void shouldIncludeNewChangesInModificationCheck() throws Exception {
        String originalNode = git.latestModification().getRevision();
        File testingFile = checkInNewRemoteFile();

        Modification modification = git.latestModification();
        assertThat(modification.getRevision(), is(not(originalNode)));
        assertThat(modification.getComment(), is("New checkin of " + testingFile.getName()));
        assertThat(modification.getModifiedFiles().size(), is(1));
        assertThat(modification.getModifiedFiles().get(0).getFileName(), is(testingFile.getName()));
    }

    @Test public void shouldIncludeChangesFromTheFutureInModificationCheck() throws Exception {
        String originalNode = git.latestModification().getRevision();
        File testingFile = checkInNewRemoteFileInFuture(THREE_DAYS_FROM_NOW);

        Modification modification = git.latestModification();
        assertThat(modification.getRevision(), is(not(originalNode)));
        assertThat(modification.getComment(), is("New checkin of " + testingFile.getName()));
        assertThat(modification.getModifiedTime(), is(THREE_DAYS_FROM_NOW));
    }

    @Test public void shouldThrowExceptionIfRepoCanNotConnectWhenModificationCheck() throws Exception {
        FileUtil.deleteFolder(repoLocation);
        try {
            git.latestModification();
            fail("Should throw exception when repo cannot connected");
        } catch (Exception e) {
            assertThat(e.getMessage(), anyOf(containsString("The remote end hung up unexpectedly"), containsString("Could not read from remote repository")));
        }
    }

    @Test
    @Ignore("It seems that Git log/diff-tree output filepath in a wrong way.  fix later")
    public void shouldSupportUTF8InCheckIn() throws IOException {
        String filename = "司徒空在此.scn";
        String message = "司徒空在此";
        gitRepo.addFileAndPush(filename, message);

        Modification modification = git.latestModification();
        assertThat(modification.getModifiedFiles().get(0).getFileName(), containsString(filename));
        assertThat(modification.getComment(), is(message));
    }

    @Test
    public void shouldParseGitOutputCorrectly() throws IOException {
        List<String> stringList = readLines(getClass().getResourceAsStream("git_sample_output.text"));

        GitModificationParser parser = new GitModificationParser();
        List<Modification> mods = parser.parse(stringList);
        assertThat(mods.size(), is(3));

        Modification mod = mods.get(2);
        assertThat(mod.getRevision(), is("46cceff864c830bbeab0a7aaa31707ae2302762f"));
        assertThat(mod.getModifiedTime(), is(DateUtils.parseISO8601("2009-08-11 12:37:09 -0700")));
        assertThat(mod.getUserDisplayName(), is("Cruise Developer <cruise@cruise-sf3.(none)>"));
        assertThat(mod.getComment(), is("author:cruise <cceuser@CceDev01.(none)>\n"
                + "node:ecfab84dd4953105e3301c5992528c2d381c1b8a\n"
                + "date:2008-12-31 14:32:40 +0800\n"
                + "description:Moving rakefile to build subdirectory for #2266\n"
                + "\n"
                + "author:CceUser <cceuser@CceDev01.(none)>\n"
                + "node:fd16efeb70fcdbe63338c49995ce9ff7659e6e77\n"
                + "date:2008-12-31 14:17:06 +0800\n"
                + "description:Adding rakefile"));
    }

    @Test
    public void shouldCleanUnversionedFilesInsideSubmodulesBeforeUpdating() throws Exception {
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos();
        String submoduleDirectoryName = "local-submodule";
        submoduleRepos.addSubmodule(SUBMODULE, submoduleDirectoryName);
        File cloneDirectory = createTempWorkingDirectory();
        GitCommand clonedCopy = new GitCommand(null, cloneDirectory, GitMaterialConfig.DEFAULT_BRANCH, false);
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        clonedCopy.cloneFrom(outputStreamConsumer, submoduleRepos.mainRepo().getUrl()); // Clone repository without submodules
        clonedCopy.fetchAndReset(outputStreamConsumer, new StringRevision("HEAD"));  // Pull submodules to working copy - Pipeline counter 1
        File unversionedFile = new File(new File(cloneDirectory, submoduleDirectoryName), "unversioned_file.txt");
        FileUtils.writeStringToFile(unversionedFile, "this is an unversioned file. lets see you deleting me.. come on.. I dare you!!!!");

        clonedCopy.fetchAndReset(outputStreamConsumer, new StringRevision("HEAD")); // Should clean unversioned file on next fetch - Pipeline counter 2

        assertThat(unversionedFile.exists(), is(false));
    }

    @Test
    public void shouldRemoveChangesToModifiedFilesInsideSubmodulesBeforeUpdating() throws Exception {
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos();
        String submoduleDirectoryName = "local-submodule";
        File cloneDirectory = createTempWorkingDirectory();

        File remoteSubmoduleLocation = submoduleRepos.addSubmodule(SUBMODULE, submoduleDirectoryName);

        /* Simulate an agent checkout of code. */
        GitCommand clonedCopy = new GitCommand(null, cloneDirectory, GitMaterialConfig.DEFAULT_BRANCH, false);
        clonedCopy.cloneFrom(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());
        clonedCopy.fetchAndReset(outputStreamConsumer, new StringRevision("HEAD"));

        /* Simulate a local modification of file inside submodule, on agent side. */
        File fileInSubmodule = allFilesIn(new File(cloneDirectory, submoduleDirectoryName), "file-").get(0);
        FileUtils.writeStringToFile(fileInSubmodule, "Some other new content.");

        /* Commit a change to the file on the repo. */
        List<Modification> modifications = submoduleRepos.modifyOneFileInSubmoduleAndUpdateMainRepo(
                remoteSubmoduleLocation, submoduleDirectoryName, fileInSubmodule.getName(), "NEW CONTENT OF FILE");

        /* Simulate start of a new build on agent. */
        clonedCopy.fetchAndReset(outputStreamConsumer, new StringRevision(modifications.get(0).getRevision()));

        assertThat(FileUtils.readFileToString(fileInSubmodule), is("NEW CONTENT OF FILE"));
    }

    @Test
    @Ignore("Test to reproduce #152 - Will fix. -Aravind")
    public void shouldAllowSubmoduleToHaveDifferentNameFromItsPath() throws Exception {
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos();

        String submoduleNameInGitModulesFile = "some-submodule-name";
        String submoduleDirectoryName = "some-submodule-path";

        submoduleRepos.addSubmodule(SUBMODULE, submoduleNameInGitModulesFile, submoduleDirectoryName);

        /* Simulate an agent checkout of code. */
        File cloneDirectory = createTempWorkingDirectory();
        GitCommand clonedCopy = new GitCommand(null, cloneDirectory, GitMaterialConfig.DEFAULT_BRANCH, false);
        clonedCopy.cloneFrom(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());
        clonedCopy.fetchAndReset(outputStreamConsumer, new StringRevision("HEAD"));
    }

    @Test
    public void shouldAllowSubmoduleUrlstoChange() throws Exception {
        InMemoryStreamConsumer outputStreamConsumer = inMemoryConsumer();
        GitSubmoduleRepos submoduleRepos = new GitSubmoduleRepos();
        String submoduleDirectoryName = "local-submodule";
        File cloneDirectory = createTempWorkingDirectory();

        File remoteSubmoduleLocation = submoduleRepos.addSubmodule(SUBMODULE, submoduleDirectoryName);

        GitCommand clonedCopy = new GitCommand(null, cloneDirectory, GitMaterialConfig.DEFAULT_BRANCH, false);
        clonedCopy.cloneFrom(outputStreamConsumer, submoduleRepos.mainRepo().getUrl());
        clonedCopy.fetchAndResetToHead(outputStreamConsumer);

        submoduleRepos.changeSubmoduleUrl(submoduleDirectoryName);

        clonedCopy.fetchAndResetToHead(outputStreamConsumer);
    }

    private List<File> allFilesIn(File directory, String prefixOfFiles) {
        return new ArrayList<File>(FileUtils.listFiles(directory, andFileFilter(fileFileFilter(), prefixFileFilter(prefixOfFiles)), null));
    }

    private File createTempWorkingDirectory() {
        File tempFile = TestFileUtil.createTempFolder("GitCommandTest" + System.currentTimeMillis());
        return new File(tempFile, "repo");
    }

    private File checkInNewRemoteFile() throws IOException {
        GitCommand remoteGit = new GitCommand(null, repoLocation, GitMaterialConfig.DEFAULT_BRANCH, false);
        File testingFile = new File(repoLocation, "testing-file" + System.currentTimeMillis() + ".txt");
        testingFile.createNewFile();
        remoteGit.add(testingFile);
        remoteGit.commit("New checkin of " + testingFile.getName());
        return testingFile;
    }

    private File checkInNewRemoteFileInFuture(Date checkinDate) throws IOException {
        GitCommand remoteGit = new GitCommand(null, repoLocation, GitMaterialConfig.DEFAULT_BRANCH, false);
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
        assertThat(dir.exists(), is(true));
        commandLine.setWorkingDir(dir);
        commandLine.runOrBomb(true, null);
    }
}
