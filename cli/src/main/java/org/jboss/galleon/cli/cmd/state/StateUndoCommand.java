/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.galleon.cli.cmd.state;

import java.io.IOException;
import org.aesh.command.CommandDefinition;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.cli.CommandExecutionException;
import org.jboss.galleon.cli.HelpDescriptions;
import org.jboss.galleon.cli.PmCommandInvocation;
import org.jboss.galleon.cli.PmSessionCommand;
import org.jboss.galleon.cli.cmd.CliErrors;
import org.jboss.galleon.cli.cmd.CommandDomain;
import org.jboss.galleon.cli.model.state.State;

/**
 * Dual command, applies in case of edit mode or not.
 *
 * @author jdenise@redhat.com
 */
@CommandDefinition(name = "undo", description = HelpDescriptions.UNDO_STATE)
public class StateUndoCommand extends PmSessionCommand {

    @Override
    protected void runCommand(PmCommandInvocation invoc) throws CommandExecutionException {
        try {
            State state = invoc.getPmSession().getState();
            if (!state.hasActions()) {
                throw new ProvisioningException(Errors.historyIsEmpty());
            }
            state.pop(invoc.getPmSession());
        } catch (ProvisioningException | IOException ex) {
            throw new CommandExecutionException(invoc.getPmSession(), CliErrors.undoFailed(), ex);
        }
    }

    @Override
    public CommandDomain getDomain() {
        return CommandDomain.EDITING;
    }

}
