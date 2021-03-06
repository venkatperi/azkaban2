package azkaban.webapp.servlet;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.ObjectName;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;

import azkaban.executor.ConnectorParams;
import azkaban.executor.ExecutorManager;
import azkaban.user.Permission;
import azkaban.user.Role;
import azkaban.user.User;
import azkaban.user.UserManager;
import azkaban.webapp.AzkabanWebServer;
import azkaban.webapp.session.Session;

/**
 * Limited set of jmx calls for when you cannot attach to the jvm
 */
public class JMXHttpServlet extends LoginAbstractAzkabanServlet implements ConnectorParams {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private static final Logger logger = Logger.getLogger(JMXHttpServlet.class.getName());

	private UserManager userManager;
	private AzkabanWebServer server;
	private ExecutorManager executorManager;
	
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
		server = (AzkabanWebServer)getApplication();
		userManager = server.getUserManager();
		executorManager = server.getExecutorManager();
	}
	
	@Override
	protected void handleGet(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {
		if (hasParam(req, "ajax")){
			Map<String,Object> ret = new HashMap<String,Object>();

			if(!hasAdminRole(session.getUser())) {
				ret.put("error", "User " + session.getUser().getUserId() + " has no permission.");
				this.writeJSON(resp, ret, true);
				return;
			}
			String ajax = getParam(req, "ajax");
			if (JMX_GET_ALL_EXECUTOR_ATTRIBUTES.equals(ajax)) {
				if (!hasParam(req, JMX_MBEAN) || !hasParam(req, JMX_HOSTPORT)) {
					ret.put("error", "Parameters '" + JMX_MBEAN + "' and '"+ JMX_HOSTPORT +"' must be set");
					this.writeJSON(resp, ret, true);
					return;
				}
				
				String hostPort = getParam(req, JMX_HOSTPORT);
				String mbean = getParam(req, JMX_MBEAN);
				Map<String, Object> result = executorManager.callExecutorJMX(hostPort, JMX_GET_ALL_MBEAN_ATTRIBUTES, mbean);
				ret = result;
			}
			else if (JMX_GET_MBEANS.equals(ajax)) {
				ret.put("mbeans", server.getMbeanNames());
			}
			else if (JMX_GET_MBEAN_INFO.equals(ajax)) {
				if (hasParam(req, JMX_MBEAN)) {
					String mbeanName = getParam(req, JMX_MBEAN);
					try {
						ObjectName name = new ObjectName(mbeanName);
						MBeanInfo info = server.getMBeanInfo(name);
						ret.put("attributes", info.getAttributes());
						ret.put("description", info.getDescription());
					} catch (Exception e) {
						logger.error(e);
						ret.put("error", "'" + mbeanName + "' is not a valid mBean name");
					}
				}
				else {
					ret.put("error", "No 'mbean' name parameter specified" );
				}
			}
			else if (JMX_GET_MBEAN_ATTRIBUTE.equals(ajax)) {
				if (!hasParam(req, JMX_MBEAN) || !hasParam(req, JMX_ATTRIBUTE)) {
					ret.put("error", "Parameters 'mbean' and 'attribute' must be set");
				}
				else {
					String mbeanName = getParam(req, JMX_MBEAN);
					String attribute = getParam(req, JMX_ATTRIBUTE);
					
					try {
						ObjectName name = new ObjectName(mbeanName);
						Object obj = server.getMBeanAttribute(name, attribute);
						ret.put("value", obj);
					} catch (Exception e) {
						logger.error(e);
						ret.put("error", "'" + mbeanName + "' is not a valid mBean name");
					}
				}
			}
			else if (JMX_GET_ALL_MBEAN_ATTRIBUTES.equals(ajax)) {
				if (!hasParam(req, JMX_MBEAN)) {
					ret.put("error", "Parameters 'mbean' must be set");
				}
				else {
					String mbeanName = getParam(req, JMX_MBEAN);
					try {
						ObjectName name = new ObjectName(mbeanName);
						MBeanInfo info = server.getMBeanInfo(name);
						
						MBeanAttributeInfo[] mbeanAttrs = info.getAttributes();
						HashMap<String, Object> attributes = new HashMap<String,Object>();

						for (MBeanAttributeInfo attrInfo: mbeanAttrs) {
							Object obj = server.getMBeanAttribute(name, attrInfo.getName());
							attributes.put(attrInfo.getName(), obj);
						}
						
						ret.put("attributes", attributes);
					} catch (Exception e) {
						logger.error(e);
						ret.put("error", "'" + mbeanName + "' is not a valid mBean name");
					}
				}
			}
			else {
				ret.put("commands", new String[] {
						JMX_GET_MBEANS, 
						JMX_GET_MBEAN_INFO+"&"+JMX_MBEAN+"=<name>", 
						JMX_GET_MBEAN_ATTRIBUTE+"&"+JMX_MBEAN+"=<name>&"+JMX_ATTRIBUTE+"=<attributename>"}
				);
			}
			this.writeJSON(resp, ret, true);
		}
		else {
			handleJMXPage(req, resp, session);
		}
	}

	private void handleJMXPage(HttpServletRequest req, HttpServletResponse resp, Session session) throws IOException {
		Page page = newPage(req, resp, session, "azkaban/webapp/servlet/velocity/jmxpage.vm");
		
		if(!hasAdminRole(session.getUser())) {
			page.add("errorMsg", "User " + session.getUser().getUserId() + " has no permission.");
			page.render();
			return;
		}

		page.add("mbeans", server.getMbeanNames());
		
		Map<String, Object> executorMBeans = new HashMap<String,Object>();
		Set<String> primaryServerHosts = executorManager.getPrimaryServerHosts();
		for (String hostPort: executorManager.getAllActiveExecutorServerHosts()) {
			try {
				Map<String, Object> mbeans = executorManager.callExecutorJMX(hostPort, JMX_GET_MBEANS, null);
	
				if (primaryServerHosts.contains(hostPort)) {
					executorMBeans.put(hostPort, mbeans.get("mbeans"));
				}
				else {
					executorMBeans.put(hostPort, mbeans.get("mbeans"));
				}
			}
			catch (IOException e) {
				logger.error("Cannot contact executor " + hostPort, e);
			}
		}
		
		page.add("remoteMBeans", executorMBeans);
		page.render();
	}
	
	@Override
	protected void handlePost(HttpServletRequest req, HttpServletResponse resp, Session session) throws ServletException, IOException {

	}
	
	private boolean hasAdminRole(User user) {
		for(String roleName: user.getRoles()) {
			Role role = userManager.getRole(roleName);
			Permission perm = role.getPermission();
			if (perm.isPermissionSet(Permission.Type.ADMIN)) {
				return true;
			}
		}
		
		return false;
	}
}
