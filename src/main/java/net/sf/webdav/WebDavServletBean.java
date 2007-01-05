package net.sf.webdav;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Vector;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import net.sf.webdav.exceptions.AccessDeniedException;
import net.sf.webdav.exceptions.ObjectAlreadyExistsException;
import net.sf.webdav.exceptions.ObjectNotFoundException;
import net.sf.webdav.exceptions.UnauthenticatedException;
import net.sf.webdav.exceptions.WebdavException;
import net.sf.webdav.fromcatalina.MD5Encoder;
import net.sf.webdav.fromcatalina.RequestUtil;
import net.sf.webdav.fromcatalina.URLEncoder;
import net.sf.webdav.fromcatalina.XMLWriter;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class WebDavServletBean extends HttpServlet {

    private static final String METHOD_HEAD = "HEAD";

    private static final String METHOD_PROPFIND = "PROPFIND";

    private static final String METHOD_PROPPATCH = "PROPPATCH";

    private static final String METHOD_MKCOL = "MKCOL";

    private static final String METHOD_COPY = "COPY";

    private static final String METHOD_MOVE = "MOVE";

    private static final String METHOD_PUT = "PUT";

    private static final String METHOD_GET = "GET";

    private static final String METHOD_OPTIONS = "OPTIONS";

    private static final String METHOD_DELETE = "DELETE";

    /**
     * MD5 message digest provider.
     */
    private MessageDigest md5Helper;

    /**
     * The MD5 helper object for this class.
     */
    protected static final MD5Encoder md5Encoder = new MD5Encoder();

    /**
     * Default depth is infite.
     */
    private static final int INFINITY = 3;

    /**
     * PROPFIND - Specify a property mask.
     */
    private static final int FIND_BY_PROPERTY = 0;

    /**
     * PROPFIND - Display all properties.
     */
    private static final int FIND_ALL_PROP = 1;

    /**
     * PROPFIND - Return property names.
     */
    private static final int FIND_PROPERTY_NAMES = 2;

    /**
     * size of the io-buffer
     */
    private static int BUF_SIZE = 50000;

    /**
     * Default namespace.
     */
    protected static final String DEFAULT_NAMESPACE = "DAV:";

    /**
     * Simple date format for the creation date ISO representation (partial).
     */
    protected static final SimpleDateFormat creationDateFormat = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'");

    /**
     * indicates that the store is readonly ?
     */
    private static final boolean readOnly = false;

    /**
     * Array containing the safe characters set.
     */
    protected static URLEncoder urlEncoder;

    private ResourceLocks resLocks = null;

    private WebdavStore store = null;

    private static int debug = -1;

    private boolean lazyFolderCreationOnPut;

    static {
        creationDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    /**
     * GMT timezone - all HTTP dates are on GMT
     */
    static {
        urlEncoder = new URLEncoder();
        urlEncoder.addSafeCharacter('-');
        urlEncoder.addSafeCharacter('_');
        urlEncoder.addSafeCharacter('.');
        urlEncoder.addSafeCharacter('*');
        urlEncoder.addSafeCharacter('/');
    }

    public WebDavServletBean() {
        this.resLocks = new ResourceLocks();
    }

    /**
     * Handles the special WebDAV methods.
     */
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String method = req.getMethod();

        if (debug == 1) {
            System.out.println("-----------");
            System.out.println("WebdavServlet\n request: method = " + method);
            System.out.println("time: " + System.currentTimeMillis());
            System.out.println("path: " + getRelativePath(req));
            System.out.println("-----------");
            Enumeration e = req.getHeaderNames();
            while (e.hasMoreElements()) {
                String s = (String) e.nextElement();
                System.out.println("header: " + s + " " + req.getHeader(s));
            }
            e = req.getAttributeNames();
            while (e.hasMoreElements()) {
                String s = (String) e.nextElement();
                System.out.println("attribute: " + s + " "
                        + req.getAttribute(s));
            }
            e = req.getParameterNames();
            while (e.hasMoreElements()) {
                String s = (String) e.nextElement();
                System.out.println("parameter: " + s + " "
                        + req.getParameter(s));
            }
        }

        try {
            store.begin(req.getUserPrincipal());
            store.checkAuthentication();
            resp.setStatus(WebdavStatus.SC_OK);

            try {
                if (method.equals(METHOD_PROPFIND)) {
                    doPropfind(req, resp);
                } else if (method.equals(METHOD_PROPPATCH)) {
                    doProppatch(req, resp);
                } else if (method.equals(METHOD_MKCOL)) {
                    doMkcol(req, resp);
                } else if (method.equals(METHOD_COPY)) {
                    doCopy(req, resp);
                } else if (method.equals(METHOD_MOVE)) {
                    doMove(req, resp);
                } else if (method.equals(METHOD_PUT)) {
                    doPut(req, resp);
                } else if (method.equals(METHOD_GET)) {
                    doGet(req, resp, true);
                } else if (method.equals(METHOD_OPTIONS)) {
                    doOptions(req, resp);
                } else if (method.equals(METHOD_HEAD)) {
                    doHead(req, resp);
                } else if (method.equals(METHOD_DELETE)) {
                    doDelete(req, resp);
                } else {
                    super.service(req, resp);
                }

                store.commit();
            } catch (IOException e) {
                e.printStackTrace();
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                store.rollback();
                throw new ServletException(e);
            }

        } catch (UnauthenticatedException e) {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);
        } catch (WebdavException e) {
            e.printStackTrace();
            throw new ServletException(e);
        }
    }

    /**
     * goes recursive through all folders. used by propfind
     * 
     * @param currentPath
     *            the current path
     * @param req
     *            HttpServletRequest
     * @param generatedXML
     * @param propertyFindType
     * @param properties
     * @param depth
     *            depth of the propfind
     * @throws IOException
     *             if an error in the underlying store occurs
     */
    private void recursiveParseProperties(String currentPath,
            HttpServletRequest req, XMLWriter generatedXML,
            int propertyFindType, Vector properties, int depth)
            throws WebdavException {

        parseProperties(req, generatedXML, currentPath, propertyFindType,
                properties);
        String[] names = store.getChildrenNames(currentPath);
        if ((names != null) && (depth > 0)) {

            for (int i = 0; i < names.length; i++) {
                String name = names[i];
                String newPath = currentPath;
                if (!(newPath.endsWith("/"))) {
                    newPath += "/";
                }
                newPath += name;
                recursiveParseProperties(newPath, req, generatedXML,
                        propertyFindType, properties, depth - 1);
            }
        }
    }

    /**
     * overwrites propNode and type, parsed from xml input stream
     * 
     * @param propNode
     * @param type
     * @param req
     *            HttpServletRequest
     * @throws ServletException
     */
    private void getPropertyNodeAndType(Node propNode, int type,
            ServletRequest req) throws ServletException {
        if (req.getContentLength() != 0) {
            DocumentBuilder documentBuilder = getDocumentBuilder();
            try {
                Document document = documentBuilder.parse(new InputSource(req
                        .getInputStream()));
                // Get the root element of the document
                Element rootElement = document.getDocumentElement();
                NodeList childList = rootElement.getChildNodes();

                for (int i = 0; i < childList.getLength(); i++) {
                    Node currentNode = childList.item(i);
                    switch (currentNode.getNodeType()) {
                    case Node.TEXT_NODE:
                        break;
                    case Node.ELEMENT_NODE:
                        if (currentNode.getNodeName().endsWith("prop")) {
                            type = FIND_BY_PROPERTY;
                            propNode = currentNode;
                        }
                        if (currentNode.getNodeName().endsWith("propname")) {
                            type = FIND_PROPERTY_NAMES;
                        }
                        if (currentNode.getNodeName().endsWith("allprop")) {
                            type = FIND_ALL_PROP;
                        }
                        break;
                    }
                }
            } catch (Exception e) {

            }
        } else {
            // no content, which means it is a allprop request
            type = FIND_ALL_PROP;
        }
    }

    /**
     * creates the parent path from the given path by removing the last '/' and
     * everything after that
     * 
     * @param path
     *            the path
     * @return parent path
     */
    private String getParentPath(String path) {
        int slash = path.lastIndexOf('/');
        if (slash != -1) {
            return path.substring(0, slash);
        }
        return null;
    }

    /**
     * Return JAXP document builder instance.
     */
    private DocumentBuilder getDocumentBuilder() throws ServletException {
        DocumentBuilder documentBuilder = null;
        DocumentBuilderFactory documentBuilderFactory = null;
        try {
            documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setNamespaceAware(true);
            documentBuilder = documentBuilderFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new ServletException("jaxp failed");
        }
        return documentBuilder;
    }

    /**
     * Return the relative path associated with this servlet.
     * 
     * @param request
     *            The servlet request we are processing
     */
    protected String getRelativePath(HttpServletRequest request) {

        // Are we being processed by a RequestDispatcher.include()?
        if (request.getAttribute("javax.servlet.include.request_uri") != null) {
            String result = (String) request
                    .getAttribute("javax.servlet.include.path_info");
            if (result == null)
                result = (String) request
                        .getAttribute("javax.servlet.include.servlet_path");
            if ((result == null) || (result.equals("")))
                result = "/";
            return (result);
        }

        // No, extract the desired path directly from the request
        String result = request.getPathInfo();
        if (result == null) {
            result = request.getServletPath();
        }
        if ((result == null) || (result.equals(""))) {
            result = "/";
        }
        return (result);

    }

    private Vector getPropertiesFromXML(Node propNode) {
        Vector properties;
        properties = new Vector();
        NodeList childList = propNode.getChildNodes();

        for (int i = 0; i < childList.getLength(); i++) {
            Node currentNode = childList.item(i);
            switch (currentNode.getNodeType()) {
            case Node.TEXT_NODE:
                break;
            case Node.ELEMENT_NODE:
                String nodeName = currentNode.getNodeName();
                String propertyName = null;
                if (nodeName.indexOf(':') != -1) {
                    propertyName = nodeName
                            .substring(nodeName.indexOf(':') + 1);
                } else {
                    propertyName = nodeName;
                }
                // href is a live property which is handled differently
                properties.addElement(propertyName);
                break;
            }
        }
        return properties;
    }

    /**
     * reads the depth header from the request and returns it as a int
     * 
     * @param req
     * @return the depth from the depth header
     */
    private int getDepth(HttpServletRequest req) {
        int depth = INFINITY;
        String depthStr = req.getHeader("Depth");
        if (depthStr != null) {
            if (depthStr.equals("0")) {
                depth = 0;
            } else if (depthStr.equals("1")) {
                depth = 1;
            } else if (depthStr.equals("infinity")) {
                depth = INFINITY;
            }
        }
        return depth;
    }

    /**
     * removes a / at the end of the path string, if present
     * 
     * @param path
     *            the path
     * @return the path without trailing /
     */
    private String getCleanPath(String path) {

        if (path.endsWith("/") && path.length() > 1)
            path = path.substring(0, path.length() - 1);
        return path;
    }

    /**
     * OPTIONS Method.</br>
     * 
     * 
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws IOException
     *             if an error in the underlying store occurs
     */
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        if (debug == 1)
            System.err.println("-- doOptions");

        String lockOwner = "doOptions" + System.currentTimeMillis()
                + req.toString();
        String path = getRelativePath(req);
        if (resLocks.lock(path, lockOwner, false, 0)) {
            try {
                resp.addHeader("DAV", "1, 2");

                String methodsAllowed = determineMethodsAllowed(store
                        .objectExists(path), store.isFolder(path));
                resp.addHeader("Allow", methodsAllowed);
                resp.addHeader("MS-Author-Via", "DAV");
            } catch (AccessDeniedException e) {
                resp.sendError(WebdavStatus.SC_FORBIDDEN);
            } catch (WebdavException e) {
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            } finally {
                resLocks.unlock(path, lockOwner);
            }
        } else {
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * PROPFIND Method.
     * 
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws IOException
     *             if an error in the underlying store occurs
     * @throws ServletException
     */
    protected void doPropfind(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        if (debug == 1)
            System.err.println("-- doPropfind");

        // Retrieve the resources
        String lockOwner = "doPropfind" + System.currentTimeMillis()
                + req.toString();
        String path = getRelativePath(req);
        int depth = getDepth(req);
        if (resLocks.lock(path, lockOwner, false, depth)) {
            try {
                if (!store.objectExists(path)) {
                    resp
                            .sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    return;
                    // we do not to continue since there is no root
                    // resource
                }

                Vector properties = null;
                path = getCleanPath(getRelativePath(req));

                int propertyFindType = FIND_ALL_PROP;
                Node propNode = null;
                getPropertyNodeAndType(propNode, propertyFindType, req);

                if (propertyFindType == FIND_BY_PROPERTY) {
                    properties = getPropertiesFromXML(propNode);
                }

                resp.setStatus(WebdavStatus.SC_MULTI_STATUS);
                resp.setContentType("text/xml; charset=UTF-8");

                // Create multistatus object
                XMLWriter generatedXML = new XMLWriter(resp.getWriter());
                generatedXML.writeXMLHeader();
                generatedXML.writeElement(null, "multistatus"
                        + generateNamespaceDeclarations(), XMLWriter.OPENING);
                if (depth == 0) {
                    parseProperties(req, generatedXML, path, propertyFindType,
                            properties);
                } else {
                    recursiveParseProperties(path, req, generatedXML,
                            propertyFindType, properties, depth);
                }
                generatedXML.writeElement(null, "multistatus",
                        XMLWriter.CLOSING);
                generatedXML.sendData();
            } catch (AccessDeniedException e) {
                resp.sendError(WebdavStatus.SC_FORBIDDEN);
            } catch (WebdavException e) {
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            } finally {
                resLocks.unlock(path, lockOwner);
            }
        } else {
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * PROPPATCH Method.
     * 
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws IOException
     *             if an error in the underlying store occurs
     */
    protected void doProppatch(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        if (debug == 1)
            System.err.println("-- doProppatch");

        if (readOnly) {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);

        } else

            resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
        // TODO implement proppatch
    }

    /**
     * GET Method
     * 
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @param includeBody
     *            if the resource content should be included or not (GET/HEAD)
     * @throws IOException
     *             if an error in the underlying store occurs
     */
    protected void doGet(HttpServletRequest req, HttpServletResponse resp,
            boolean includeBody) throws ServletException, IOException {

        String path = getRelativePath(req);

        if (debug == 1)
            System.err.println("-- doGet " + path);

        try {
            if (store.isFolder(path)) {
                String dftIndxFile = getServletConfig().getInitParameter(
                        "default-index-file");
                if (dftIndxFile != null) {
                    resp.sendRedirect(resp.encodeRedirectURL(req
                            .getRequestURI()
                            + dftIndxFile));
                    return;
                }
            }
            if (!store.objectExists(path)) {
                String insteadOf404 = getServletConfig().getInitParameter(
                        "instead-of-404");
                if (insteadOf404 != null) {
                    path = insteadOf404;
                }
            }

        } catch (WebdavException e) {}

        String lockOwner = "doGet" + System.currentTimeMillis()
                + req.toString();

        if (resLocks.lock(path, lockOwner, false, 0)) {
            try {

                if (store.isResource(path)) {
                    // path points to a file but ends with / or \
                    if (path.endsWith("/") || (path.endsWith("\\"))) {
                        resp.sendError(HttpServletResponse.SC_NOT_FOUND, req
                                .getRequestURI());
                    } else {

                        // setting headers
                        long lastModified = store.getLastModified(path)
                                .getTime();
                        resp.setDateHeader("last-modified", lastModified);

                        long resourceLength = store.getResourceLength(path);
                        String contLength = getServletConfig()
                                .getInitParameter("no-content-length-headers");
                        if (contLength == null) {
                            if (resourceLength > 0) {
                                if (resourceLength <= Integer.MAX_VALUE) {
                                    resp.setContentLength((int) resourceLength);
                                } else {
                                    resp.setHeader("content-length", ""
                                            + resourceLength);
                                    // is "content-length" the right header?
                                    // is
                                    // long
                                    // a valid format?
                                }
                            }
                        }

                        String mimeType = getServletContext().getMimeType(path);
                        if (mimeType != null) {
                            resp.setContentType(mimeType);
                        } else {
                            int lastSlash = path.replace('\\', '/')
                                    .lastIndexOf('/');
                            int lastDot = path.indexOf(".", lastSlash);
                            if (lastDot == -1) {
                                resp.setContentType("text/html");
                            }
                        }

                        if (includeBody) {
                            OutputStream out = resp.getOutputStream();
                            InputStream in = store.getResourceContent(path);
                            try {
                                int read = -1;
                                byte[] copyBuffer = new byte[BUF_SIZE];

                                while ((read = in.read(copyBuffer, 0,
                                        copyBuffer.length)) != -1) {
                                    out.write(copyBuffer, 0, read);
                                }

                            } finally {

                                in.close();
                                out.flush();
                                out.close();
                            }
                        }
                    }
                } else {
                    if (includeBody && store.isFolder(path)) {
                        // TODO some folder response (for browsers, DAV tools
                        // use propfind) in html?
                        OutputStream out = resp.getOutputStream();
                        String[] children = store.getChildrenNames(path);
                        StringBuffer childrenTemp = new StringBuffer();
                        childrenTemp.append("Contents of this Folder:\n");
                        for (int i = 0; i < children.length; i++) {
                            childrenTemp.append(children[i]);
                            childrenTemp.append("\n");
                        }
                        out.write(childrenTemp.toString().getBytes());
                    } else {
                        if (!store.objectExists(path)) {
                            resp.sendError(HttpServletResponse.SC_NOT_FOUND,
                                    req.getRequestURI());
                        }

                    }
                }
            } catch (AccessDeniedException e) {
                resp.sendError(WebdavStatus.SC_FORBIDDEN);
            } catch (ObjectAlreadyExistsException e) {
                resp.sendError(WebdavStatus.SC_NOT_FOUND, req.getRequestURI());
            } catch (WebdavException e) {
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            } finally {
                resLocks.unlock(path, lockOwner);
            }
        } else {
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * HEAD Method.
     * 
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws IOException
     *             if an error in the underlying store occurs
     */
    protected void doHead(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        if (debug == 1)
            System.err.println("-- doHead");
        doGet(req, resp, false);
    }

    /**
     * MKCOL Method.
     * 
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws IOException
     *             if an error in the underlying store occurs
     */
    protected void doMkcol(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        if (debug == 1)
            System.err.println("-- doMkcol");

        if (req.getContentLength() != 0) {
            resp.sendError(WebdavStatus.SC_NOT_IMPLEMENTED);
        } else {

            if (!readOnly) {
                // not readonly
                String path = getRelativePath(req);
                String parentPath = getParentPath(path);
                String lockOwner = "doMkcol" + System.currentTimeMillis()
                        + req.toString();
                if (resLocks.lock(path, lockOwner, true, 0)) {
                    try {
                        if (parentPath != null && store.isFolder(parentPath)) {
                            boolean isFolder = store.isFolder(path);
                            if (!store.objectExists(path)) {
                                try {
                                    store.createFolder(path);
                                } catch (ObjectAlreadyExistsException e) {
                                    String methodsAllowed = determineMethodsAllowed(
                                            true, isFolder);
                                    resp.addHeader("Allow", methodsAllowed);
                                    resp
                                            .sendError(WebdavStatus.SC_METHOD_NOT_ALLOWED);
                                }
                            } else {
                                // object already exists
                                String methodsAllowed = determineMethodsAllowed(
                                        true, isFolder);
                                resp.addHeader("Allow", methodsAllowed);
                                resp
                                        .sendError(WebdavStatus.SC_METHOD_NOT_ALLOWED);
                            }
                        } else {
                            resp.sendError(WebdavStatus.SC_CONFLICT);
                        }
                    } catch (AccessDeniedException e) {
                        resp.sendError(WebdavStatus.SC_FORBIDDEN);
                    } catch (WebdavException e) {
                        resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                    } finally {
                        resLocks.unlock(path, lockOwner);
                    }
                } else {
                    resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                }
            } else {
                resp.sendError(WebdavStatus.SC_FORBIDDEN);
            }
        }

    }

    /**
     * DELETE Method.
     * 
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws IOException
     *             if an error in the underlying store occurs
     */
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        if (debug == 1)
            System.err.println("-- doDelete");

        if (!readOnly) {
            String path = getRelativePath(req);
            String lockOwner = "doDelete" + System.currentTimeMillis()
                    + req.toString();
            if (resLocks.lock(path, lockOwner, true, -1)) {
                try {
                    Hashtable errorList = new Hashtable();
                    deleteResource(path, errorList, req, resp);
                    if (!errorList.isEmpty()) {
                        sendReport(req, resp, errorList);
                    }
                } catch (AccessDeniedException e) {
                    resp.sendError(WebdavStatus.SC_FORBIDDEN);
                } catch (ObjectAlreadyExistsException e) {
                    resp.sendError(WebdavStatus.SC_NOT_FOUND, req
                            .getRequestURI());
                } catch (WebdavException e) {
                    resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                } finally {
                    resLocks.unlock(path, lockOwner);
                }
            } else {
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            }
        } else {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);
        }

    }

    /**
     * Process a POST request for the specified resource.
     * 
     * @param req
     *            The servlet request we are processing
     * @param resp
     *            The servlet response we are creating
     * 
     * @exception WebdavException
     *                if an error in the underlying store occurs
     */
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        if (debug == 1)
            System.err.println("-- doPut");

        if (!readOnly) {
            String path = getRelativePath(req);
            String parentPath = getParentPath(path);
            String lockOwner = "doPut" + System.currentTimeMillis()
                    + req.toString();
            if (resLocks.lock(path, lockOwner, true, -1)) {
                try {
                    if (parentPath != null && !store.isFolder(parentPath)
                            && lazyFolderCreationOnPut) {
                        store.createFolder(parentPath);
                    }
                    if (!store.isFolder(path)) {
                        if (!store.objectExists(path)) {
                            store.createResource(path);
                            resp.setStatus(HttpServletResponse.SC_CREATED);
                        } else {
                            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        }
                        store.setResourceContent(path, req.getInputStream(),
                                null, null);
                        resp.setContentLength((int) store
                                .getResourceLength(path));
                    }
                } catch (AccessDeniedException e) {
                    resp.sendError(WebdavStatus.SC_FORBIDDEN);
                } catch (WebdavException e) {
                    resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                } finally {
                    resLocks.unlock(path, lockOwner);
                }
            } else {
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            }
        } else {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);
        }

    }

    /**
     * COPY Method.
     * 
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws WebdavException
     *             if an error in the underlying store occurs
     * @throws IOException
     *             when an error occurs while sending the response
     */
    protected void doCopy(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        if (debug == 1)
            System.err.println("-- doCopy");

        String path = getRelativePath(req);
        if (!readOnly) {
            String lockOwner = "doCopy" + System.currentTimeMillis()
                    + req.toString();
            if (resLocks.lock(path, lockOwner, false, -1)) {
                try {
                    copyResource(req, resp);
                } catch (AccessDeniedException e) {
                    resp.sendError(WebdavStatus.SC_FORBIDDEN);
                } catch (ObjectAlreadyExistsException e) {
                    resp.sendError(WebdavStatus.SC_CONFLICT, req
                            .getRequestURI());
                } catch (ObjectNotFoundException e) {
                    resp.sendError(WebdavStatus.SC_NOT_FOUND, req
                            .getRequestURI());
                } catch (WebdavException e) {
                    resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                } finally {
                    resLocks.unlock(path, lockOwner);
                }
            } else {
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            }

        } else {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);
        }

    }

    /**
     * MOVE Method.
     * 
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws ServletException
     * @throws WebdavException
     *             if an error in the underlying store occurs
     * @throws IOException
     *             when an error occurs while sending the response
     */
    protected void doMove(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        if (!readOnly) {

            if (debug == 1)
                System.err.println("-- doMove");

            String path = getRelativePath(req);
            String lockOwner = "doMove" + System.currentTimeMillis()
                    + req.toString();
            if (resLocks.lock(path, lockOwner, false, -1)) {
                try {
                    if (copyResource(req, resp)) {

                        Hashtable errorList = new Hashtable();
                        deleteResource(path, errorList, req, resp);
                        if (!errorList.isEmpty()) {
                            sendReport(req, resp, errorList);
                        }

                    } else {
                        resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                    }
                } catch (AccessDeniedException e) {
                    resp.sendError(WebdavStatus.SC_FORBIDDEN);
                } catch (ObjectAlreadyExistsException e) {
                    resp.sendError(WebdavStatus.SC_NOT_FOUND, req
                            .getRequestURI());
                } catch (WebdavException e) {
                    resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                } finally {
                    resLocks.unlock(path, lockOwner);
                }
            } else {
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            }
        } else {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);

        }
    }

    /**
     * Generate the namespace declarations.
     * 
     * @return the namespace declaration
     */
    private String generateNamespaceDeclarations() {
        return " xmlns=\"" + DEFAULT_NAMESPACE + "\"";
    }

    /**
     * Copy a resource.
     * 
     * @param req
     *            Servlet request
     * @param resp
     *            Servlet response
     * @return true if the copy is successful
     * @throws WebdavException
     *             if an error in the underlying store occurs
     * @throws IOException
     *             when an error occurs while sending the response
     */
    private boolean copyResource(HttpServletRequest req,
            HttpServletResponse resp) throws WebdavException, IOException {

        // Parsing destination header

        String destinationPath = req.getHeader("Destination");

        if (destinationPath == null) {
            resp.sendError(WebdavStatus.SC_BAD_REQUEST);
            return false;
        }

        // Remove url encoding from destination
        destinationPath = RequestUtil.URLDecode(destinationPath, "UTF8");

        int protocolIndex = destinationPath.indexOf("://");
        if (protocolIndex >= 0) {
            // if the Destination URL contains the protocol, we can safely
            // trim everything upto the first "/" character after "://"
            int firstSeparator = destinationPath
                    .indexOf("/", protocolIndex + 4);
            if (firstSeparator < 0) {
                destinationPath = "/";
            } else {
                destinationPath = destinationPath.substring(firstSeparator);
            }
        } else {
            String hostName = req.getServerName();
            if ((hostName != null) && (destinationPath.startsWith(hostName))) {
                destinationPath = destinationPath.substring(hostName.length());
            }

            int portIndex = destinationPath.indexOf(":");
            if (portIndex >= 0) {
                destinationPath = destinationPath.substring(portIndex);
            }

            if (destinationPath.startsWith(":")) {
                int firstSeparator = destinationPath.indexOf("/");
                if (firstSeparator < 0) {
                    destinationPath = "/";
                } else {
                    destinationPath = destinationPath.substring(firstSeparator);
                }
            }
        }

        // Normalise destination path (remove '.' and '..')
        destinationPath = normalize(destinationPath);

        String contextPath = req.getContextPath();
        if ((contextPath != null) && (destinationPath.startsWith(contextPath))) {
            destinationPath = destinationPath.substring(contextPath.length());
        }

        String pathInfo = req.getPathInfo();
        if (pathInfo != null) {
            String servletPath = req.getServletPath();
            if ((servletPath != null)
                    && (destinationPath.startsWith(servletPath))) {
                destinationPath = destinationPath.substring(servletPath
                        .length());
            }
        }

        String path = getRelativePath(req);

        // if source = destination
        if (path.equals(destinationPath)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
        }

        // Parsing overwrite header

        boolean overwrite = true;
        String overwriteHeader = req.getHeader("Overwrite");

        if (overwriteHeader != null) {
            overwrite = overwriteHeader.equalsIgnoreCase("T");
        }

        // Overwriting the destination
        String lockOwner = "copyResource" + System.currentTimeMillis()
                + req.toString();
        if (resLocks.lock(destinationPath, lockOwner, true, -1)) {
            try {

                // Retrieve the resources
                if (!store.objectExists(path)) {
                    resp
                            .sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    return false;
                }

                boolean exists = store.objectExists(destinationPath);
                Hashtable errorList = new Hashtable();

                if (overwrite) {

                    // Delete destination resource, if it exists
                    if (exists) {
                        deleteResource(destinationPath, errorList, req, resp);

                    } else {
                        resp.setStatus(WebdavStatus.SC_CREATED);
                    }
                } else {

                    // If the destination exists, then it's a conflict
                    if (exists) {
                        resp.sendError(WebdavStatus.SC_PRECONDITION_FAILED);
                        return false;
                    } else {
                        resp.setStatus(WebdavStatus.SC_CREATED);
                    }

                }
                copy(path, destinationPath, errorList, req, resp);
                if (!errorList.isEmpty()) {
                    sendReport(req, resp, errorList);
                }

            } finally {
                resLocks.unlock(destinationPath, lockOwner);
            }
        } else {
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            return false;
        }
        return true;

    }

    /**
     * copies the specified resource(s) to the specified destination.
     * preconditions must be handled by the caller. Standard status codes must
     * be handled by the caller. a multi status report in case of errors is
     * created here.
     * 
     * @param sourcePath
     *            path from where to read
     * @param destinationPath
     *            path where to write
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws WebdavException
     *             if an error in the underlying store occurs
     * @throws IOException
     */
    private void copy(String sourcePath, String destinationPath,
            Hashtable errorList, HttpServletRequest req,
            HttpServletResponse resp) throws WebdavException, IOException {

        if (store.isResource(sourcePath)) {
            store.createResource(destinationPath);
            store.setResourceContent(destinationPath, store
                    .getResourceContent(sourcePath), null, null);
        } else {

            if (store.isFolder(sourcePath)) {
                copyFolder(sourcePath, destinationPath, errorList, req, resp);
            } else {
                resp.sendError(WebdavStatus.SC_NOT_FOUND);
            }
        }
    }

    /**
     * helper method of copy() recursively copies the FOLDER at source path to
     * destination path
     * 
     * @param sourcePath
     *            where to read
     * @param destinationPath
     *            where to write
     * @param errorList
     *            all errors that ocurred
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws WebdavException
     *             if an error in the underlying store occurs
     */
    private void copyFolder(String sourcePath, String destinationPath,
            Hashtable errorList, HttpServletRequest req,
            HttpServletResponse resp) throws WebdavException {

        store.createFolder(destinationPath);
        boolean infiniteDepth = true;
        if (req.getHeader("depth") != null) {
            if (req.getHeader("depth").equals("0")) {
                infiniteDepth = false;
            }
        }
        if (infiniteDepth) {
            String[] children = store.getChildrenNames(sourcePath);

            for (int i = children.length - 1; i >= 0; i--) {
                children[i] = "/" + children[i];
                try {
                    if (store.isResource(sourcePath + children[i])) {
                        store.createResource(destinationPath + children[i]);
                        store.setResourceContent(destinationPath + children[i],
                                store.getResourceContent(sourcePath
                                        + children[i]), null, null);

                    } else {
                        copyFolder(sourcePath + children[i], destinationPath
                                + children[i], errorList, req, resp);
                    }
                } catch (AccessDeniedException e) {
                    errorList.put(destinationPath + children[i], new Integer(
                            WebdavStatus.SC_FORBIDDEN));
                } catch (ObjectNotFoundException e) {
                    errorList.put(destinationPath + children[i], new Integer(
                            WebdavStatus.SC_NOT_FOUND));
                } catch (ObjectAlreadyExistsException e) {
                    errorList.put(destinationPath + children[i], new Integer(
                            WebdavStatus.SC_CONFLICT));
                } catch (WebdavException e) {
                    errorList.put(destinationPath + children[i], new Integer(
                            WebdavStatus.SC_INTERNAL_SERVER_ERROR));
                }
            }
        }
    }

    /**
     * deletes the recources at "path"
     * 
     * @param path
     *            the folder to be deleted
     * @param errorList
     *            all errors that ocurred
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws WebdavException
     *             if an error in the underlying store occurs
     * @throws IOException
     *             when an error occurs while sending the response
     */
    private void deleteResource(String path, Hashtable errorList,
            HttpServletRequest req, HttpServletResponse resp)
            throws IOException, WebdavException {

        resp.setStatus(WebdavStatus.SC_NO_CONTENT);
        if (!readOnly) {

            if (store.isResource(path)) {
                store.removeObject(path);
            } else {
                if (store.isFolder(path)) {

                    deleteFolder(path, errorList, req, resp);
                    store.removeObject(path);
                } else {
                    resp.sendError(WebdavStatus.SC_NOT_FOUND);
                }
            }

        } else {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);
        }
    }

    /**
     * 
     * helper method of deleteResource() deletes the folder and all of its
     * contents
     * 
     * @param path
     *            the folder to be deleted
     * @param errorList
     *            all errors that ocurred
     * @param req
     *            HttpServletRequest
     * @param resp
     *            HttpServletResponse
     * @throws WebdavException
     *             if an error in the underlying store occurs
     */
    private void deleteFolder(String path, Hashtable errorList,
            HttpServletRequest req, HttpServletResponse resp)
            throws WebdavException {

        String[] children = store.getChildrenNames(path);
        for (int i = children.length - 1; i >= 0; i--) {
            children[i] = "/" + children[i];
            try {
                if (store.isResource(path + children[i])) {
                    store.removeObject(path + children[i]);

                } else {
                    deleteFolder(path + children[i], errorList, req, resp);

                    store.removeObject(path + children[i]);

                }
            } catch (AccessDeniedException e) {
                errorList.put(path + children[i], new Integer(
                        WebdavStatus.SC_FORBIDDEN));
            } catch (ObjectNotFoundException e) {
                errorList.put(path + children[i], new Integer(
                        WebdavStatus.SC_NOT_FOUND));
            } catch (WebdavException e) {
                errorList.put(path + children[i], new Integer(
                        WebdavStatus.SC_INTERNAL_SERVER_ERROR));
            }
        }

    }

    /**
     * Return a context-relative path, beginning with a "/", that represents the
     * canonical version of the specified path after ".." and "." elements are
     * resolved out. If the specified path attempts to go outside the boundaries
     * of the current context (i.e. too many ".." path elements are present),
     * return <code>null</code> instead.
     * 
     * @param path
     *            Path to be normalized
     */
    protected String normalize(String path) {

        if (path == null)
            return null;

        // Create a place for the normalized path
        String normalized = path;

        if (normalized.equals("/."))
            return "/";

        // Normalize the slashes and add leading slash if necessary
        if (normalized.indexOf('\\') >= 0)
            normalized = normalized.replace('\\', '/');
        if (!normalized.startsWith("/"))
            normalized = "/" + normalized;

        // Resolve occurrences of "//" in the normalized path
        while (true) {
            int index = normalized.indexOf("//");
            if (index < 0)
                break;
            normalized = normalized.substring(0, index)
                    + normalized.substring(index + 1);
        }

        // Resolve occurrences of "/./" in the normalized path
        while (true) {
            int index = normalized.indexOf("/./");
            if (index < 0)
                break;
            normalized = normalized.substring(0, index)
                    + normalized.substring(index + 2);
        }

        // Resolve occurrences of "/../" in the normalized path
        while (true) {
            int index = normalized.indexOf("/../");
            if (index < 0)
                break;
            if (index == 0)
                return (null); // Trying to go outside our context
            int index2 = normalized.lastIndexOf('/', index - 1);
            normalized = normalized.substring(0, index2)
                    + normalized.substring(index + 3);
        }

        // Return the normalized path that we have completed
        return (normalized);

    }

    /**
     * Send a multistatus element containing a complete error report to the
     * client.
     * 
     * @param req
     *            Servlet request
     * @param resp
     *            Servlet response
     * @param errorList
     *            List of error to be displayed
     */
    private void sendReport(HttpServletRequest req, HttpServletResponse resp,
            Hashtable errorList) throws IOException {

        resp.setStatus(WebdavStatus.SC_MULTI_STATUS);

        String absoluteUri = req.getRequestURI();
        String relativePath = getRelativePath(req);

        XMLWriter generatedXML = new XMLWriter();
        generatedXML.writeXMLHeader();

        generatedXML.writeElement(null, "multistatus"
                + generateNamespaceDeclarations(), XMLWriter.OPENING);

        Enumeration pathList = errorList.keys();
        while (pathList.hasMoreElements()) {

            String errorPath = (String) pathList.nextElement();
            int errorCode = ((Integer) errorList.get(errorPath)).intValue();

            generatedXML.writeElement(null, "response", XMLWriter.OPENING);

            generatedXML.writeElement(null, "href", XMLWriter.OPENING);
            String toAppend = errorPath.substring(relativePath.length());
            if (!toAppend.startsWith("/"))
                toAppend = "/" + toAppend;
            generatedXML.writeText(absoluteUri + toAppend);
            generatedXML.writeElement(null, "href", XMLWriter.CLOSING);
            generatedXML.writeElement(null, "status", XMLWriter.OPENING);
            generatedXML.writeText("HTTP/1.1 " + errorCode + " "
                    + WebdavStatus.getStatusText(errorCode));
            generatedXML.writeElement(null, "status", XMLWriter.CLOSING);

            generatedXML.writeElement(null, "response", XMLWriter.CLOSING);

        }

        generatedXML.writeElement(null, "multistatus", XMLWriter.CLOSING);

        Writer writer = resp.getWriter();
        writer.write(generatedXML.toString());
        writer.close();

    }

    /**
     * Propfind helper method.
     * 
     * @param req
     *            The servlet request
     * @param generatedXML
     *            XML response to the Propfind request
     * @param path
     *            Path of the current resource
     * @param type
     *            Propfind type
     * @param propertiesVector
     *            If the propfind type is find properties by name, then this
     *            Vector contains those properties
     */
    private void parseProperties(HttpServletRequest req,
            XMLWriter generatedXML, String path, int type,
            Vector propertiesVector) throws WebdavException {

        String creationdate = getISOCreationDate(store.getCreationDate(path)
                .getTime());
        boolean isFolder = store.isFolder(path);
        SimpleDateFormat formatter = new SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("GMT"));
        String lastModified = formatter.format(store.getLastModified(path));
        String resourceLength = String.valueOf(store.getResourceLength(path));

        // ResourceInfo resourceInfo = new ResourceInfo(path, resources);

        generatedXML.writeElement(null, "response", XMLWriter.OPENING);
        String status = new String("HTTP/1.1 " + WebdavStatus.SC_OK + " "
                + WebdavStatus.getStatusText(WebdavStatus.SC_OK));

        // Generating href element
        generatedXML.writeElement(null, "href", XMLWriter.OPENING);

        String href = req.getContextPath();
        if ((href.endsWith("/")) && (path.startsWith("/")))
            href += path.substring(1);
        else
            href += path;
        if ((isFolder) && (!href.endsWith("/")))
            href += "/";

        generatedXML.writeText(rewriteUrl(href));

        generatedXML.writeElement(null, "href", XMLWriter.CLOSING);

        String resourceName = path;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash != -1)
            resourceName = resourceName.substring(lastSlash + 1);

        switch (type) {

        case FIND_ALL_PROP:

            generatedXML.writeElement(null, "propstat", XMLWriter.OPENING);
            generatedXML.writeElement(null, "prop", XMLWriter.OPENING);

            generatedXML.writeProperty(null, "creationdate", creationdate);
            generatedXML.writeElement(null, "displayname", XMLWriter.OPENING);
            generatedXML.writeData(resourceName);
            generatedXML.writeElement(null, "displayname", XMLWriter.CLOSING);
            if (!isFolder) {
                generatedXML.writeProperty(null, "getlastmodified",
                        lastModified);
                generatedXML.writeProperty(null, "getcontentlength",
                        resourceLength);
                String contentType = getServletContext().getMimeType(path);
                if (contentType != null) {
                    generatedXML.writeProperty(null, "getcontenttype",
                            contentType);
                }
                generatedXML.writeProperty(null, "getetag", getETag(path,
                        resourceLength, lastModified));
                generatedXML.writeElement(null, "resourcetype",
                        XMLWriter.NO_CONTENT);
            } else {
                generatedXML.writeElement(null, "resourcetype",
                        XMLWriter.OPENING);
                generatedXML.writeElement(null, "collection",
                        XMLWriter.NO_CONTENT);
                generatedXML.writeElement(null, "resourcetype",
                        XMLWriter.CLOSING);
            }

            generatedXML.writeProperty(null, "source", "");
            generatedXML.writeElement(null, "prop", XMLWriter.CLOSING);
            generatedXML.writeElement(null, "status", XMLWriter.OPENING);
            generatedXML.writeText(status);
            generatedXML.writeElement(null, "status", XMLWriter.CLOSING);
            generatedXML.writeElement(null, "propstat", XMLWriter.CLOSING);

            break;

        case FIND_PROPERTY_NAMES:

            generatedXML.writeElement(null, "propstat", XMLWriter.OPENING);
            generatedXML.writeElement(null, "prop", XMLWriter.OPENING);

            generatedXML.writeElement(null, "creationdate",
                    XMLWriter.NO_CONTENT);
            generatedXML
                    .writeElement(null, "displayname", XMLWriter.NO_CONTENT);
            if (!isFolder) {
                generatedXML.writeElement(null, "getcontentlanguage",
                        XMLWriter.NO_CONTENT);
                generatedXML.writeElement(null, "getcontentlength",
                        XMLWriter.NO_CONTENT);
                generatedXML.writeElement(null, "getcontenttype",
                        XMLWriter.NO_CONTENT);
                generatedXML
                        .writeElement(null, "getetag", XMLWriter.NO_CONTENT);
                generatedXML.writeElement(null, "getlastmodified",
                        XMLWriter.NO_CONTENT);
            }
            generatedXML.writeElement(null, "resourcetype",
                    XMLWriter.NO_CONTENT);
            generatedXML.writeElement(null, "source", XMLWriter.NO_CONTENT);
            generatedXML.writeElement(null, "lockdiscovery",
                    XMLWriter.NO_CONTENT);

            generatedXML.writeElement(null, "prop", XMLWriter.CLOSING);
            generatedXML.writeElement(null, "status", XMLWriter.OPENING);
            generatedXML.writeText(status);
            generatedXML.writeElement(null, "status", XMLWriter.CLOSING);
            generatedXML.writeElement(null, "propstat", XMLWriter.CLOSING);

            break;

        case FIND_BY_PROPERTY:

            Vector propertiesNotFound = new Vector();

            // Parse the list of properties

            generatedXML.writeElement(null, "propstat", XMLWriter.OPENING);
            generatedXML.writeElement(null, "prop", XMLWriter.OPENING);

            Enumeration properties = propertiesVector.elements();

            while (properties.hasMoreElements()) {

                String property = (String) properties.nextElement();

                if (property.equals("creationdate")) {
                    generatedXML.writeProperty(null, "creationdate",
                            creationdate);
                } else if (property.equals("displayname")) {
                    generatedXML.writeElement(null, "displayname",
                            XMLWriter.OPENING);
                    generatedXML.writeData(resourceName);
                    generatedXML.writeElement(null, "displayname",
                            XMLWriter.CLOSING);
                } else if (property.equals("getcontentlanguage")) {
                    if (isFolder) {
                        propertiesNotFound.addElement(property);
                    } else {
                        generatedXML.writeElement(null, "getcontentlanguage",
                                XMLWriter.NO_CONTENT);
                    }
                } else if (property.equals("getcontentlength")) {
                    if (isFolder) {
                        propertiesNotFound.addElement(property);
                    } else {
                        generatedXML.writeProperty(null, "getcontentlength",
                                resourceLength);
                    }
                } else if (property.equals("getcontenttype")) {
                    if (isFolder) {
                        propertiesNotFound.addElement(property);
                    } else {
                        generatedXML.writeProperty(null, "getcontenttype",
                                getServletContext().getMimeType(path));
                    }
                } else if (property.equals("getetag")) {
                    if (isFolder) {
                        propertiesNotFound.addElement(property);
                    } else {
                        generatedXML.writeProperty(null, "getetag", getETag(
                                path, resourceLength, lastModified));
                    }
                } else if (property.equals("getlastmodified")) {
                    if (isFolder) {
                        propertiesNotFound.addElement(property);
                    } else {
                        generatedXML.writeProperty(null, "getlastmodified",
                                lastModified);
                    }
                } else if (property.equals("resourcetype")) {
                    if (isFolder) {
                        generatedXML.writeElement(null, "resourcetype",
                                XMLWriter.OPENING);
                        generatedXML.writeElement(null, "collection",
                                XMLWriter.NO_CONTENT);
                        generatedXML.writeElement(null, "resourcetype",
                                XMLWriter.CLOSING);
                    } else {
                        generatedXML.writeElement(null, "resourcetype",
                                XMLWriter.NO_CONTENT);
                    }
                } else if (property.equals("source")) {
                    generatedXML.writeProperty(null, "source", "");
                } else {
                    propertiesNotFound.addElement(property);
                }

            }

            generatedXML.writeElement(null, "prop", XMLWriter.CLOSING);
            generatedXML.writeElement(null, "status", XMLWriter.OPENING);
            generatedXML.writeText(status);
            generatedXML.writeElement(null, "status", XMLWriter.CLOSING);
            generatedXML.writeElement(null, "propstat", XMLWriter.CLOSING);

            Enumeration propertiesNotFoundList = propertiesNotFound.elements();

            if (propertiesNotFoundList.hasMoreElements()) {

                status = new String("HTTP/1.1 " + WebdavStatus.SC_NOT_FOUND
                        + " "
                        + WebdavStatus.getStatusText(WebdavStatus.SC_NOT_FOUND));

                generatedXML.writeElement(null, "propstat", XMLWriter.OPENING);
                generatedXML.writeElement(null, "prop", XMLWriter.OPENING);

                while (propertiesNotFoundList.hasMoreElements()) {
                    generatedXML.writeElement(null,
                            (String) propertiesNotFoundList.nextElement(),
                            XMLWriter.NO_CONTENT);
                }

                generatedXML.writeElement(null, "prop", XMLWriter.CLOSING);
                generatedXML.writeElement(null, "status", XMLWriter.OPENING);
                generatedXML.writeText(status);
                generatedXML.writeElement(null, "status", XMLWriter.CLOSING);
                generatedXML.writeElement(null, "propstat", XMLWriter.CLOSING);

            }

            break;

        }

        generatedXML.writeElement(null, "response", XMLWriter.CLOSING);

    }

    /**
     * Get the ETag associated with a file.
     * 
     * @param path
     *            path to the resource
     * @param resourceLength
     *            filesize
     * @param lastModified
     *            last-modified date
     * @return the ETag
     */
    protected String getETag(String path, String resourceLength,
            String lastModified) {
        // TODO create a real (?) ETag
        // parameter "path" is not used at the monent
        return "W/\"" + resourceLength + "-" + lastModified + "\"";

    }

    /**
     * URL rewriter.
     * 
     * @param path
     *            Path which has to be rewiten
     * @return the rewritten path
     */
    protected String rewriteUrl(String path) {
        return urlEncoder.encode(path);
    }

    /**
     * Get creation date in ISO format.
     * 
     * @param creationDate
     *            the date in milliseconds
     * @return the Date in ISO format
     */
    private String getISOCreationDate(long creationDate) {
        StringBuffer creationDateValue = new StringBuffer(creationDateFormat
                .format(new Date(creationDate)));
        /*
         * int offset = Calendar.getInstance().getTimeZone().getRawOffset() /
         * 3600000; // FIXME ? if (offset < 0) { creationDateValue.append("-");
         * offset = -offset; } else if (offset > 0) {
         * creationDateValue.append("+"); } if (offset != 0) { if (offset < 10)
         * creationDateValue.append("0"); creationDateValue.append(offset +
         * ":00"); } else { creationDateValue.append("Z"); }
         */
        return creationDateValue.toString();
    }

    /**
     * Determines the methods normally allowed for the resource.
     * 
     * @param exists
     *            does the resource exist?
     * @param isFolder
     *            is the resource a folder?
     * @return all allowed methods, separated by commas
     */
    private String determineMethodsAllowed(boolean exists, boolean isFolder) {
        StringBuffer methodsAllowed = new StringBuffer();
        try {
            if (exists) {
                methodsAllowed
                        .append("OPTIONS, GET, HEAD, POST, DELETE, TRACE");
                methodsAllowed
                        .append(", PROPPATCH, COPY, MOVE, LOCK, UNLOCK, PROPFIND");
                if (isFolder) {
                    methodsAllowed.append(", PUT");
                }
                return methodsAllowed.toString();
            }
        } catch (Exception e) {
            // we do nothing, just return less allowed methods

        }
        methodsAllowed.append("OPTIONS, MKCOL, PUT, LOCK");
        return methodsAllowed.toString();

    }

    public void setMd5Helper(MessageDigest md5Helper) {
        this.md5Helper = md5Helper;
    }

    public void setStore(WebdavStore store) {
        this.store = store;
    }

    public void setLazyFolderCreationOnPut(boolean lazyFolderCreationOnPut) {
        this.lazyFolderCreationOnPut = lazyFolderCreationOnPut;
    }

    public void setDebug(int debug) {
        this.debug = debug;
    }

}