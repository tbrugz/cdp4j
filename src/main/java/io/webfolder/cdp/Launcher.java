/**
 * cdp4j Commercial License
 *
 * Copyright 2017, 2019 WebFolder OÜ
 *
 * Permission  is hereby  granted,  to "____" obtaining  a  copy of  this software  and
 * associated  documentation files  (the "Software"), to deal in  the Software  without
 * restriction, including without limitation  the rights  to use, copy, modify,  merge,
 * publish, distribute  and sublicense  of the Software,  and to permit persons to whom
 * the Software is furnished to do so, subject to the following conditions:
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR  IMPLIED,
 * INCLUDING  BUT NOT  LIMITED  TO THE  WARRANTIES  OF  MERCHANTABILITY, FITNESS  FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL  THE AUTHORS  OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
 * OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.webfolder.cdp;

import static io.webfolder.cdp.session.SessionFactory.DEFAULT_HOST;
import static java.lang.Long.toHexString;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Locale.ENGLISH;
import static java.util.concurrent.ThreadLocalRandom.current;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import io.webfolder.cdp.exception.CdpException;
import io.webfolder.cdp.logger.CdpLoggerType;
import io.webfolder.cdp.session.SessionFactory;

public class Launcher extends AbstractLauncher {

    private static final String OS = getProperty("os.name").toLowerCase(ENGLISH);

    private static final boolean WINDOWS = OS.startsWith("windows");

    private static final boolean OSX = OS.startsWith("mac");

    private ProcessManager processManager = new AdaptiveProcessManager();

    public Launcher(CdpLoggerType loggerType) {
        this(new SessionFactory(loggerType));
    }

    public Launcher() {
        this(new SessionFactory());
    }

    public Launcher(int port) {
        this(new SessionFactory(port));
    }

    public Launcher(final SessionFactory factory) {
        super(factory);
    }

    private String getCustomChromeBinary() {
        String chromeBinary = getProperty("chrome_binary");
        if (chromeBinary != null) {
            File chromeExecutable = new File(chromeBinary);
            if (chromeExecutable.exists() && chromeExecutable.canExecute()) {
                return chromeExecutable.getAbsolutePath();
            }
        }
        return null;
    }

    @Override
    public String findChrome() {
        String chromeExecutablePath = null;
        chromeExecutablePath = getCustomChromeBinary();
        if (chromeExecutablePath == null && WINDOWS) {
            chromeExecutablePath = findChromeWinPath();
        }
        if (chromeExecutablePath == null && OSX) {
            chromeExecutablePath = findChromeOsxPath();
        }
        if (chromeExecutablePath == null && !WINDOWS) {
            chromeExecutablePath = "google-chrome";
        }
        return chromeExecutablePath;
    }

    public String findChromeWinPath() {
        try {
            for (String path : getChromeWinPaths()) {
                final Process process = getRuntime().exec(new String[]{
                        "cmd", "/c", "echo", path
                });
                final int exitCode = process.waitFor();
                if (exitCode == 0) {
                	try (InputStream is = process.getInputStream()) {
                		String location = toString(is).trim().replace("\"", "");
                		File chrome = new File(location);
                		if (chrome.exists() && chrome.canExecute()) {
                			return chrome.toString();
                		}
                	}
                }
            }
            throw new CdpException("Unable to find chrome.exe");
        } catch (Throwable e) {
            // ignore
        }
        return null;
    }

    /**
     * Tests whether chrome/chromium is installed.
     * 
     * @return {@code true} if browser is found on predefined paths
     */
    public boolean isChromeInstalled() {
    	String path = null;
    	try {
    		path = findChrome();
    	} catch (CdpException e) {
    		if ("Unable to find chrome.exe".equals(e.getMessage())) {
    			// ignore
    		} else {
    			throw e;
    		}
    	}
    	return path != null ? true : false;
    }

    protected List<String> getChromeWinPaths() {
    	List<String> prefixes = asList("%localappdata%",
    								   "%programfiles%",
    								   "%programfiles(x86)%");
    	List<String> suffixes = asList(
    			"\\Google\\Chrome SxS\\Application\\chrome.exe",
    			"\\Google\\Chrome\\Application\\chrome.exe");
    	List<String> installations = new ArrayList<String>(prefixes.size() * suffixes.size());
    	for (String prefix : prefixes) {
    		for (String suffix : suffixes) {
    			installations.add(prefix + suffix);
    		}
    	}
    	return installations;
    }

    public String findChromeOsxPath() {
        for (String path : getChromeOsxPaths()) {
            final File chrome = new File(path);
            if (chrome.exists() && chrome.canExecute()) {
                return chrome.toString();
            }
        }
        return null;
    }

    protected List<String> getChromeOsxPaths() {
        return asList(
                "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary", // Chrome Canary
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"                // Chrome Stable
        );
    }

    public SessionFactory launch(Path chromeExecutablePath, List<String> arguments) {
        return launch(chromeExecutablePath.toString(), arguments);
    }

    public SessionFactory launch(Path chromeExecutablePath) {
        return launch(chromeExecutablePath, emptyList());
    }

    @Override
    protected void internalLaunch(List<String> list, List<String> arguments) {
        boolean foundUserDataDir = arguments.stream().anyMatch(arg -> arg.startsWith("--user-data-dir="));

        if (!foundUserDataDir) {
            Path remoteProfileData = get(getProperty("java.io.tmpdir")).resolve("remote-profile");
            list.add(format("--user-data-dir=%s", remoteProfileData.toString()));
        }

        if (!DEFAULT_HOST.equals(factory.getHost())) {
            list.add(format("--remote-debugging-address=%s", factory.getHost()));
        }

        try {
            String cdp4jId = toHexString(current().nextLong());
            list.add("--cdp4jId=" + cdp4jId);
            ProcessBuilder builder = new ProcessBuilder(list);
            builder.environment().put("CDP4J_ID", cdp4jId);
            Process process = builder.start();

            process.getOutputStream().close();
            process.getInputStream().close();
            process.getErrorStream().close();

            if (!process.isAlive()) {
                throw new CdpException("No process: the chrome process is not alive.");
            }

            processManager.setProcess(new CdpProcess(process, cdp4jId));
        } catch (IOException e) {
            throw new CdpException(e);
        }
    }

    protected String toString(InputStream is) {
        try (Scanner scanner = new Scanner(is)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    public void setProcessManager(ProcessManager processManager) {
        this.processManager = processManager;
    }

    public ProcessManager getProcessManager() {
        return processManager;
    }

    @Override
    public void kill() {
        getProcessManager().kill();
    }
}
