package org.ovirt.engine.core.bll.aaa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.api.extensions.ExtKey;
import org.ovirt.engine.api.extensions.ExtMap;
import org.ovirt.engine.api.extensions.aaa.Authz;
import org.ovirt.engine.core.aaa.AuthenticationProfile;
import org.ovirt.engine.core.aaa.AuthenticationProfileRepository;
import org.ovirt.engine.core.bll.CommandBase;
import org.ovirt.engine.core.bll.NonTransactiveCommandAttribute;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.CreateUserSessionParameters;
import org.ovirt.engine.core.common.businessentities.ActionGroup;
import org.ovirt.engine.core.common.businessentities.aaa.DbGroup;
import org.ovirt.engine.core.common.businessentities.aaa.DbUser;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.DbGroupDao;
import org.ovirt.engine.core.dao.DbUserDao;
import org.ovirt.engine.core.dao.PermissionDao;
import org.ovirt.engine.core.dao.RoleDao;

@NonTransactiveCommandAttribute
public class CreateUserSessionCommand<T extends CreateUserSessionParameters> extends CommandBase<T> {
    private static final Guid BOTTOM_OBJECT_ID = new Guid("BBB00000-0000-0000-0000-123456789BBB");
    private static final Guid DEFAULT_USER_ID = new Guid("00000000-0000-0000-0000-000000000000");

    @Inject
    private SessionDataContainer sessionDataContainer;

    @Inject
    private DbUserDao dbUserDao;

    @Inject
    private DbGroupDao dbGroupDao;

    @Inject
    private RoleDao roleDao;

    @Inject
    private PermissionDao permissionDao;

    public CreateUserSessionCommand(T parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    private DbUser buildUser(T params, String authzName) {
        DbUser user = dbUserDao.getByExternalId(authzName, params.getPrincipalId());
        if (user == null) {
            user = new DbUser();
            user.setId(DEFAULT_USER_ID);
        }
        user.setEmail(params.getEmail());
        user.setLoginName(params.getPrincipalName());
        List<Guid> groupIds = new ArrayList<>();
        List<ExtMap> groupExtMapIds = new ArrayList<>();
        flatGroups(params.getGroupIds(), groupExtMapIds);
        for (ExtMap group : groupExtMapIds) {
            DbGroup dbGroup = dbGroupDao.getByExternalId(authzName, group.<String>get(Authz.GroupRecord.ID));
            if (dbGroup != null) {
                groupIds.add(dbGroup.getId());
            }
        }
        user.setGroupIds(groupIds);
        return user;
    }

    @Override
    protected void executeCommand() {
        final AuthenticationProfile profile = AuthenticationProfileRepository.getInstance()
                .getProfile(getParameters().getProfileName());
        if (profile == null) {
            setSucceeded(false);
        } else {
            final DbUser user = buildUser(getParameters(), profile.getAuthzName());
            boolean isAdmin = !roleDao.getAnyAdminRoleForUserAndGroups(user.getId(),
                    StringUtils.join(user.getGroupIds(), ",")).isEmpty();
            user.setAdmin(isAdmin);

            if (getParameters().isAdminRequired() && !isAdmin) {
                setSucceeded(false);
            } else if (permissionDao.getEntityPermissionsForUserAndGroups(user.getId(),
                    StringUtils.join(user.getGroupIds(), ","),
                    ActionGroup.LOGIN,
                    BOTTOM_OBJECT_ID,
                    VdcObjectType.Bottom,
                    true) == null) {
                setSucceeded(false);
            } else {
                String engineSessionId = sessionDataContainer.generateEngineSessionId();
                sessionDataContainer.setSourceIp(engineSessionId, getParameters().getSourceIp());
                sessionDataContainer.setUser(engineSessionId, user);
                sessionDataContainer.refresh(engineSessionId);
                sessionDataContainer.setProfile(engineSessionId, profile);
                sessionDataContainer.setPrincipalName(engineSessionId, getParameters().getPrincipalName());
                sessionDataContainer.setSsoAccessToken(engineSessionId, getParameters().getSsoToken());
                getReturnValue().setActionReturnValue(engineSessionId);
                setSucceeded(true);
            }
        }
    }

    private static List<ExtMap> flatGroups(Collection<Map> groupIds, List<ExtMap> accumulator) {
        for (Map group : groupIds) {
            accumulator.add((ExtMap) group);
            flatGroups((ExtMap) group, Authz.GroupRecord.GROUPS, accumulator);
        }
        return accumulator;
    }

    private static List<ExtMap> flatGroups(ExtMap entity, ExtKey key, List<ExtMap> accumulator) {
        for (ExtMap group : entity.<List<ExtMap>>get(key, Collections.<ExtMap>emptyList())) {
            accumulator.add(group);
            flatGroups(group, Authz.GroupRecord.GROUPS, accumulator);
        }
        return accumulator;
    }

    @Override
    protected boolean isUserAuthorizedToRunAction() {
        return true;
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        return Collections.emptyList();
    }

}
