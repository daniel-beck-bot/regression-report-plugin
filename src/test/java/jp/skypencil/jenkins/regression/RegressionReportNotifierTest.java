package jp.skypencil.jenkins.regression;


import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.List;

import javax.mail.Address;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import com.google.common.collect.Lists;

import hudson.Launcher;
import hudson.console.AnnotatedLargeText;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.User;
import hudson.tasks.Mailer;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.CaseResult.Status;
import hudson.tasks.test.AbstractTestResultAction;
import jenkins.model.JenkinsLocationConfiguration;

public class RegressionReportNotifierTest {
    private BuildListener listener;
    private Launcher launcher;
    private AbstractBuild<?, ?> build;

    @Rule
    public JenkinsRule rule = new JenkinsRule();

    @Before
    public void setUp() throws Exception {
        listener = mock(BuildListener.class);
        launcher = mock(Launcher.class);
        build = mock(AbstractBuild.class);
        PrintStream logger = mock(PrintStream.class);
        doReturn("").when(build).getUrl();
        doReturn(logger).when(listener).getLogger();

        // set administrator's address to avoid NPE
        JenkinsLocationConfiguration configuration = JenkinsLocationConfiguration.get();
        configuration.setAdminAddress("admin@address.com");
    }

    @Test
    public void testCompileErrorOccured() throws InterruptedException,
            IOException {
        doReturn(null).when(build).getAction(AbstractTestResultAction.class);
        RegressionReportNotifier notifier = new RegressionReportNotifier("",
                false);

        assertThat(notifier.perform(build, launcher, listener), is(true));
    }

    @Test
    public void testSend() throws InterruptedException, MessagingException, IOException {
        makeRegression();

        RegressionReportNotifier notifier = new RegressionReportNotifier(
                "author@mail.com", false);
        MockedMailSender mailSender = new MockedMailSender();
        notifier.setMailSender(mailSender);

        assertThat(notifier.perform(build, launcher, listener), is(true));
        assertThat(mailSender.getSentMessage(), is(notNullValue()));
        Address[] to = mailSender.getSentMessage().getRecipients(
                RecipientType.TO);
        assertThat(to.length, is(1));
        assertThat(to[0].toString(), is(equalTo("author@mail.com")));
    }

    @Test
    public void testSendToCulprits() throws InterruptedException,
            MessagingException, IOException {
        makeRegression();

        RegressionReportNotifier notifier = new RegressionReportNotifier(
                "author@mail.com", true);
        MockedMailSender mailSender = new MockedMailSender();
        notifier.setMailSender(mailSender);

        assertThat(notifier.perform(build, launcher, listener), is(true));
        assertThat(mailSender.getSentMessage(), is(notNullValue()));
        Address[] to = mailSender.getSentMessage().getRecipients(
                RecipientType.TO);
        assertThat(to.length, is(2));
        assertThat(to[0].toString(), is(equalTo("author@mail.com")));
        assertThat(to[1].toString(), is(equalTo("culprit@mail.com")));
    }

    private void makeRegression() {
        AbstractTestResultAction<?> result = mock(AbstractTestResultAction.class);
        doReturn(result).when(build).getAction(AbstractTestResultAction.class);
        doReturn(Result.FAILURE).when(build).getResult();
        User culprit = mock(User.class);
        doReturn("culprit").when(culprit).getId();
        doReturn(new Mailer.UserProperty("culprit@mail.com")).when(culprit)
                .getProperty(eq(Mailer.UserProperty.class));
        doReturn(new ChangeLogSetMock(build).withChangeBy(culprit)).when(build)
                .getChangeSet();

        CaseResult failedTest = mock(CaseResult.class);
        doReturn(Status.REGRESSION).when(failedTest).getStatus();
        List<CaseResult> failedTests = Lists.newArrayList(failedTest);
        doReturn(failedTests).when(result).getFailedTests();
    }

    @Test
    public void testAttachLogFile() throws InterruptedException, MessagingException, IOException {
        makeRegression();

        File f = new File(getClass().getResource("/log").getPath());
        AnnotatedLargeText<?> text = new AnnotatedLargeText<>(f, Charset.defaultCharset(), false, build);
        doReturn(text).when(build).getLogText();
        doReturn(f.getAbsoluteFile().getParentFile()).when(build).getRootDir();

        RegressionReportNotifier notifier = new RegressionReportNotifier("author@mail.com", false, true);
        MockedMailSender mailSender = new MockedMailSender();
        notifier.setMailSender(mailSender);

        assertThat(build.getLogText(), is(notNullValue()));
        assertThat(notifier.perform(build, launcher, listener), is(true));
        assertThat(mailSender.getSentMessage(), is(notNullValue()));

        Address[] to = mailSender.getSentMessage().getRecipients(RecipientType.TO);
        assertThat(to.length, is(1));
        assertThat(to[0].toString(), is(equalTo("author@mail.com")));

        assertThat(notifier.getAttachLog(), is(true));
        assertThat(mailSender.getSentMessage().getContent() instanceof Multipart, is(true));

        Multipart multipartContent = (Multipart) mailSender.getSentMessage().getContent();
        assertThat(multipartContent.getCount(), is(2));
        assertThat(((MimeBodyPart)multipartContent.getBodyPart(1)).getDisposition(), is(equalTo(Part.ATTACHMENT)));
        assertThat(((MimeBodyPart)multipartContent.getBodyPart(0)).getDisposition(), is(nullValue()));
    }

    private static final class MockedMailSender implements
            RegressionReportNotifier.MailSender {
        private MimeMessage sentMessage;

        @Override
        public void send(MimeMessage message) throws MessagingException {
            sentMessage = message;
        }

        public MimeMessage getSentMessage() {
            return sentMessage;
        }
    }
}
