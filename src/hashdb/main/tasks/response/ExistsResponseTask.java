package hashdb.main.tasks.response;

import hashdb.Settings;
import hashdb.Utilities;
import hashdb.communication.ConnectionInstance;
import hashdb.communication.protos.ServerToServer.JobResponse;
import hashdb.exceptions.ConnectionNotActiveException;
import hashdb.exceptions.NoSuchServerException;
import hashdb.exceptions.SomethingWentHorriblyWrong;
import hashdb.main.Server;
import hashdb.main.structures.RemoteTaskPool;
import hashdb.main.tasks.forwarding.AckForwarding;
import hashdb.main.tasks.forwarding.ForwardingTask;
import hashdb.main.tasks.remote.RemoteCheckIfExists;
import hashdb.main.threads.WorkerThread;
import hashdb.storage.protocol.external.RemoteServerInfo;

/**
 * Created with IntelliJ IDEA.
 * User: filip
 * Date: 5/19/13
 * Time: 2:05 PM
 * To change this template use File | Settings | File Templates.
 */
public class ExistsResponseTask
		extends BooleanResponseTask {

    private final boolean client;
    private ConnectionInstance ci;
    private final short replyTo;
    private short localID;
    private final byte[] key;

    public ExistsResponseTask(ConnectionInstance ci, short replyTo, short localID, byte[] key) {
        super();
        client = replyTo == Settings.CommunicationCodes.CLIENT;
        this.key=key;
        if (client) {
            this.replyTo = (short) Server.getInstance().getID();
            this.ci = ci;
        } else {
            this.replyTo = replyTo;
            if (replyTo != Server.getInstance().getID())
                this.ci = RemoteServerInfo.getFromID(replyTo).getServerConnectionInstance().getOutgoing();
            else {
                this.ci=null;
            }
            this.localID = localID;
        }
    }
    private boolean myClientRemoteTask = false;
    public void work() {
        log.info("Exists Response Task started");
        if (ci==null) {
            ForwardingTask ft = RemoteTaskPool.fetchTask(localID);
            ci = ft.getCi();
            log.info("CI was null but now is set!");
            myClientRemoteTask = true;
        }
        if (status) {
            synchronized (ci) {
                if (ci.isBeingUsed()) {
                    log.info("CI is being used, will retry in a moment");
                    WorkerThread.addTask(this);
                    return;
                }
                ci.startUsing();
            }
            sucessfullReply(myClientRemoteTask);
            log.info("Everything is ok");
            return;
        } else {
            RemoteServerInfo nextInfo = RemoteServerInfo.getMyNext();
            if (nextInfo.getServerID() == Server.getInstance().getID()) {
                iAmOnlyOneAndICannot();
                return;
            }

            try {
                if (!client && nextInfo.getServerID() == RemoteServerInfo.findID(key).getServerID()) {
                    everyoneWasVisitedAndNobodyCan(myClientRemoteTask);
                    return;
                }
            } catch (NoSuchServerException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
            if (client || myClientRemoteTask) {
                localID = RemoteTaskPool.addTask(new AckForwarding(ci));
            }
            WorkerThread.addTask(new RemoteCheckIfExists(RemoteServerInfo.getMyNext().getServerConnectionInstance().getOutgoing(),replyTo,localID,key));
        }
    }

    private void everyoneWasVisitedAndNobodyCan(boolean myClientRemoteTask) {
        synchronized (ci) {
            if (ci.isBeingUsed()) {
                WorkerThread.addTask(this);
                return;
            }
            ci.startUsing();
        }
        try {
            if (!myClientRemoteTask) {
                ci.send(JobResponse.getInstance().getCode());
                ci.send(localID);
            }
            ci.send(Settings.CommunicationCodes.NACK);
            return;
        } catch (ConnectionNotActiveException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } finally {
            ci.stopUsing();
        }
        return;
    }

    private void iAmOnlyOneAndICannot() {
        ci.startUsing();
        try {
            ci.send(Settings.CommunicationCodes.NACK);
        } catch (ConnectionNotActiveException e) {
            throw new SomethingWentHorriblyWrong();
        } finally {
            ci.stopUsing();		}
    }

    private void sucessfullReply(boolean myClientRemote) {
        log.info("Sucessfull reply started");
        try {
            if (!client && !myClientRemote) {
                ci.send(JobResponse.getInstance().getCode());
                ci.send(localID);
            }
            Utilities.sendAck(ci);
        } catch (ConnectionNotActiveException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } finally {
            ci.stopUsing();
            log.info("Sucessfull reply finished");
        }
    }
}