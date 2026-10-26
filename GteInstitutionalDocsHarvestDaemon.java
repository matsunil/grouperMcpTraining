import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.RequestOptions;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

import edu.internet2.middleware.grouper.app.gsh.template.GrouperTemplateDaemon;
import edu.internet2.middleware.grouper.app.loader.OtherJobTemplateInput;
import edu.internet2.middleware.grouper.app.loader.db.Hib3GrouperLoaderLog;
import edu.internet2.middleware.grouper.util.GrouperUtil;

/**
 * Grouper Training Environment (GTE) daemon for the MCP AI training, institutional documentation lesson.
 *
 * <p>Pulls the simulated institutional documentation pages from the Grouper wiki (every page with the
 * Confluence label {@link #LABEL}), converts each one to markdown, and writes one .md file per page into
 * {@link #OUTPUT_DIRECTORY}.  The MCP doc_search tool indexes that directory with Lucene when it is
 * configured as a filesystem doc search source:</p>
 *
 * <pre>
 * grouper.mcp.docSearch.gteDocs.directory = /opt/grouper/docs/gte
 * </pre>
 *
 * <p>Pipeline:</p>
 * <ol>
 *   <li>Find the pages: Confluence CQL search for the label, over the public REST API (anonymous, the
 *       Grouper space is public), using the Playwright request context</li>
 *   <li>For each page, open it in headless Chromium (Confluence Cloud renders the page with JavaScript, so a
 *       plain HTTP fetch of the page URL does not have the content)</li>
 *   <li>Take the page body, remove the Navigation include at the top of every Grouper wiki page, and convert
 *       the HTML to markdown with Flexmark</li>
 *   <li>Write {@code <pageId>.md} with YAML frontmatter (title, url), which doc_search uses for the result
 *       title and link</li>
 *   <li>Delete .md files for pages that no longer have the label</li>
 * </ol>
 *
 * <p>This is a compiled Java daemon: grouperGshTemplate.gteDocsHarvest (templateType=daemon,
 * templateMode=compiled) in grouper.properties, scheduled by otherJob.gteDocsHarvestDaemon in
 * grouper-loader.properties.  Chromium needs its OS libraries in the container
 * (GROUPER_PLAYWRIGHT_INSTALL_OS_LIBS=true, or setupFilesForComponent_playwrightInstallOsLibsHelper as root).
 * Playwright downloads the browser itself on the first run, so the container needs internet access.</p>
 */
public class GteInstitutionalDocsHarvestDaemon extends GrouperTemplateDaemon {

  /** Grouper wiki, the public custom domain (anonymous reads work there) */
  public static final String WIKI_BASE_URL = "https://docs.grouper.internet2.edu/wiki";

  /** Confluence label on the simulated institutional documentation pages */
  public static final String LABEL = "gte-institutional-doc";

  /** Directory doc_search reads, must match grouper.mcp.docSearch.gteDocs.directory */
  public static final String OUTPUT_DIRECTORY = "/opt/grouper/docs/gte";

  /**
   * Where Chromium was downloaded, as tomcat:
   * java -cp "/opt/grouper/grouperWebapp/WEB-INF/lib/*" com.microsoft.playwright.CLI install chromium
   */
  public static final String BROWSERS_PATH = "/home/tomcat/.cache/ms-playwright";

  /** Selector for the page title in Confluence Cloud */
  private static final String TITLE_SELECTOR = "#heading-title-text";

  /** Selector for the rendered page body in Confluence Cloud */
  private static final String BODY_SELECTOR = "#main-content .ak-renderer-document";

  /**
   * JavaScript run in the page: copy the page body, remove included pages (the Navigation include renders
   * as a nested renderer document), and return the HTML.  Works on a copy so the page itself is untouched.
   */
  private static final String BODY_HTML_WITHOUT_INCLUDES_JS =
      "(selector) => {\n"
      + "  const body = document.querySelector(selector);\n"
      + "  if (!body) { return null; }\n"
      + "  const copy = body.cloneNode(true);\n"
      + "  copy.querySelectorAll('.ak-renderer-document').forEach(nested => {\n"
      + "    let top = nested;\n"
      + "    while (top.parentElement && top.parentElement !== copy) { top = top.parentElement; }\n"
      + "    if (top.parentElement === copy) { top.remove(); }\n"
      + "  });\n"
      + "  return copy.innerHTML;\n"
      + "}";

  /**
   * Called by the daemon bridge once per scheduled run.
   */
  @Override
  public void runDaemon(OtherJobTemplateInput otherJobTemplateInput) {

    Hib3GrouperLoaderLog hib3GrouperLoaderLog = otherJobTemplateInput.getHib3GrouperLoaderLog();

    // the compiled daemon input does not have a debug map, keep our own and put it on the loader log
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    try {
      harvest(debugMap, hib3GrouperLoaderLog);
    } finally {
      hib3GrouperLoaderLog.setJobMessage(GrouperUtil.mapToString(debugMap));
    }
  }

  /**
   * Find the labeled pages, convert each to markdown, and write the files.
   * @param debugMap what happened, for the loader log
   * @param hib3GrouperLoaderLog counts go here so the daemon screen shows them
   */
  public static void harvest(Map<String, Object> debugMap, Hib3GrouperLoaderLog hib3GrouperLoaderLog) {

    File outputDirectory = new File(OUTPUT_DIRECTORY);
    GrouperUtil.mkdirs(outputDirectory);
    debugMap.put("outputDirectory", outputDirectory.getAbsolutePath());

    // file names written this run, so files for pages that lost the label can be removed afterward
    Set<String> fileNamesWritten = new HashSet<String>();
    int errorCount = 0;

    // Point Playwright at the browser downloaded ahead of time as tomcat, and do not download from the daemon.
    // The Tomcat process does not run with the same HOME as a tomcat login shell, so without this Playwright
    // does not see ~/.cache/ms-playwright and fails with "Failed to install browsers".
    Map<String, String> playwrightEnv = new LinkedHashMap<String, String>();
    playwrightEnv.put("PLAYWRIGHT_BROWSERS_PATH", BROWSERS_PATH);
    playwrightEnv.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
    debugMap.put("browsersPath", BROWSERS_PATH);

    Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(playwrightEnv));
    try {

      List<String[]> pages = findLabeledPages(playwright);
      debugMap.put("pagesWithLabel", pages.size());

      // fail rather than delete every file when the search returns nothing (e.g. Confluence is down),
      // so doc_search keeps the last good copy
      if (pages.size() == 0) {
        throw new RuntimeException("No pages found with label '" + LABEL + "', not changing " + OUTPUT_DIRECTORY);
      }

      Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
      Page page = browser.newPage();

      for (String[] pageIdTitleUrl : pages) {
        String pageId = pageIdTitleUrl[0];
        String url = pageIdTitleUrl[2];
        try {
          String markdown = pageToMarkdown(page, url);

          // title from the rendered page, falling back to the search result title
          String title = StringUtils.defaultIfBlank(page.locator(TITLE_SELECTOR).first().innerText(), pageIdTitleUrl[1]).trim();

          String fileName = pageId + ".md";
          GrouperUtil.saveStringIntoFile(new File(outputDirectory, fileName), frontmatter(title, url) + markdown);
          fileNamesWritten.add(fileName);
          debugMap.put("wrote " + fileName, title + " (" + markdown.length() + " chars)");

        } catch (RuntimeException re) {
          // one bad page should not stop the others; it is reported and the daemon fails at the end
          errorCount++;
          debugMap.put("error " + pageId, re.getMessage());
        }
      }
      browser.close();

    } finally {
      playwright.close();
    }

    // remove files for pages which are no longer labeled
    int deletedCount = 0;
    File[] existingFiles = outputDirectory.listFiles();
    if (existingFiles != null) {
      for (File existingFile : existingFiles) {
        if (existingFile.getName().endsWith(".md") && !fileNamesWritten.contains(existingFile.getName())) {
          GrouperUtil.deleteFile(existingFile);
          deletedCount++;
          debugMap.put("deleted " + existingFile.getName(), "no longer has label " + LABEL);
        }
      }
    }

    hib3GrouperLoaderLog.setInsertCount(fileNamesWritten.size());
    hib3GrouperLoaderLog.setDeleteCount(deletedCount);
    hib3GrouperLoaderLog.setTotalCount(fileNamesWritten.size());

    if (errorCount > 0) {
      throw new RuntimeException(errorCount + " page(s) failed, see the job message");
    }
  }

  /**
   * Confluence CQL search for the label.  Anonymous, over the Playwright request context (no browser needed
   * for JSON).
   * @return page id, title, and full page url for each labeled page
   */
  public static List<String[]> findLabeledPages(Playwright playwright) {

    APIRequestContext request = playwright.request().newContext();
    try {
      APIResponse response = request.get(WIKI_BASE_URL + "/rest/api/content/search",
          RequestOptions.create()
            .setQueryParam("cql", "label=\"" + LABEL + "\" and type=page")
            .setQueryParam("limit", "100"));
      if (!response.ok()) {
        throw new RuntimeException("Confluence search failed: " + response.status() + " " + response.statusText());
      }

      JsonNode searchResults = GrouperUtil.jsonJacksonNode(response.text());
      ArrayNode results = GrouperUtil.jsonJacksonGetArrayNode(searchResults, "results");

      List<String[]> pages = new ArrayList<String[]>();
      for (int i = 0; i < (results == null ? 0 : results.size()); i++) {
        JsonNode result = results.get(i);
        String webui = GrouperUtil.jsonJacksonGetString(GrouperUtil.jsonJacksonGetNode(result, "_links"), "webui");
        pages.add(new String[] {
            GrouperUtil.jsonJacksonGetString(result, "id"),
            GrouperUtil.jsonJacksonGetString(result, "title"),
            // webui starts with /spaces/..., the wiki base url has the /wiki part
            WIKI_BASE_URL + webui});
      }
      return pages;
    } finally {
      request.dispose();
    }
  }

  /**
   * Open the page in the browser, wait for Confluence to render it, and convert the body to markdown.
   */
  public static String pageToMarkdown(Page page, String url) {

    Response response = page.navigate(url);
    if (response == null || !response.ok()) {
      throw new RuntimeException("Could not open " + url + ": " + (response == null ? "no response" : response.status()));
    }

    // Confluence Cloud renders the body after the page loads
    page.locator(BODY_SELECTOR).first().waitFor();

    String html = (String)page.evaluate(BODY_HTML_WITHOUT_INCLUDES_JS, BODY_SELECTOR);
    if (StringUtils.isBlank(html)) {
      throw new RuntimeException("No page body found at " + url);
    }

    String markdown = FlexmarkHtmlConverter.builder().build().convert(html);

    // make wiki-relative links absolute, so the AI can cite them
    markdown = StringUtils.replace(markdown, "](/wiki/", "](" + WIKI_BASE_URL + "/");
    return markdown;
  }

  /**
   * Test harness: run the harvest outside the daemon and print what happened.
   */
  public static void main(String[] args) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    try {
      harvest(debugMap, new Hib3GrouperLoaderLog());
    } finally {
      System.out.println(GrouperUtil.mapToString(debugMap));
    }
  }

  /**
   * YAML frontmatter which doc_search reads for the document title and link.
   */
  public static String frontmatter(String title, String url) {
    return "---\n"
        + "title: \"" + StringUtils.replace(title, "\"", "'") + "\"\n"
        + "url: \"" + url + "\"\n"
        + "---\n\n";
  }

}
