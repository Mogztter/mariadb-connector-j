/*
MariaDB Client for Java

Copyright (c) 2012 Monty Program Ab.

This library is free software; you can redistribute it and/or modify it under
the terms of the GNU Lesser General Public License as published by the Free
Software Foundation; either version 2.1 of the License, or (at your option)
any later version.

This library is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
for more details.

You should have received a copy of the GNU Lesser General Public License along
with this library; if not, write to Monty Program Ab info@montyprogram.com.

This particular MariaDB Client for Java file is work
derived from a Drizzle-JDBC. Drizzle-JDBC file which is covered by subject to
the following copyright and notice provisions:

Copyright (c) 2009-2011, Marcus Eriksson

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:
Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

Neither the name of the driver nor the names of its contributors may not be
used to endorse or promote products derived from this software without specific
prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
OF SUCH DAMAGE.
*/

package org.mariadb.jdbc.internal.mysql.listener.impl;

import org.mariadb.jdbc.HostAddress;
import org.mariadb.jdbc.JDBCUrl;
import org.mariadb.jdbc.internal.SQLExceptionMapper;
import org.mariadb.jdbc.internal.common.QueryException;
import org.mariadb.jdbc.internal.common.UrlHAMode;
import org.mariadb.jdbc.internal.mysql.HandleErrorResult;
import org.mariadb.jdbc.internal.mysql.MySQLProtocol;
import org.mariadb.jdbc.internal.mysql.Protocol;
import org.mariadb.jdbc.internal.mysql.listener.AbstractMastersListener;
import org.mariadb.jdbc.internal.mysql.listener.tools.SearchFilter;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MastersFailoverListener extends AbstractMastersListener {
    private final UrlHAMode mode;

    public MastersFailoverListener(final JDBCUrl jdbcUrl) {
        super(jdbcUrl);
        this.mode = jdbcUrl.getHaMode();

    }

    public void initializeConnection() throws QueryException {
        this.currentProtocol = null;
//        log.trace("launching initial loop");
        reconnectFailedConnection(new SearchFilter(true, false));
//        log.trace("launching initial loop end");

    }

    public void preExecute() throws QueryException {
        //if connection is closed or failed on slave
        if (this.currentProtocol != null && this.currentProtocol.isClosed()) {
            if (!isExplicitClosed() && jdbcUrl.getOptions().autoReconnect) {
                try {
                    reconnectFailedConnection(new SearchFilter(isMasterHostFail(), false, !currentReadOnlyAsked.get(), currentReadOnlyAsked.get()));
                } catch (QueryException e) {
                }
            } else
                throw new QueryException("Connection is closed", (short) -1, SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION.getSqlState());
        }
    }

    public boolean shouldReconnect() {
        return isMasterHostFail();
    }

    @Override
    public void preClose()  throws SQLException {
        setExplicitClosed(true);
        proxy.lock.writeLock().lock();
        try {
            if (currentProtocol != null && this.currentProtocol.isConnected()) this.currentProtocol.close();
        } finally {
            if (!UrlHAMode.NONE.equals(mode)) {
                proxy.lock.writeLock().unlock();
                if (scheduledFailover != null) {
                    scheduledFailover.cancel(true);
                    isLooping.set(false);
                }
                executorService.shutdownNow();
                try {
                    executorService.awaitTermination(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
//                    log.trace("executorService interrupted");
                }
            }
        }
//        log.trace("preClose connections");
    }

    @Override
    public HandleErrorResult primaryFail(Method method, Object[] args) throws Throwable {
        boolean alreadyClosed = !currentProtocol.isConnected();
        try {
            if (currentProtocol != null && currentProtocol.isConnected() && currentProtocol.ping()) {
//                if (log.isDebugEnabled())
//                    log.debug("Primary node [" + currentProtocol.getHostAddress().toString() + "] connection re-established");

                // if in transaction cannot be sure that the last query has been received by server of not, so rollback.
                if (currentProtocol.inTransaction()) {
                        currentProtocol.rollback();
                }
                return new HandleErrorResult(true);
            }
        } catch (QueryException e) {
            proxy.lock.writeLock().lock();
            try {
                currentProtocol.close();
            } finally {
                proxy.lock.writeLock().unlock();
            }
            if (setMasterHostFail()) addToBlacklist(currentProtocol.getHostAddress());
        }

        try {
            reconnectFailedConnection(new SearchFilter(true, false));
            if (!UrlHAMode.NONE.equals(mode)) launchFailLoopIfNotlaunched(true);
            if (alreadyClosed) return relaunchOperation(method, args);
            return new HandleErrorResult(true);
        } catch (Exception e) {
            if (!UrlHAMode.NONE.equals(mode)) launchFailLoopIfNotlaunched(true);
            return new HandleErrorResult();
        }
    }

    /**
     * Loop to connect
     *
     * @throws QueryException if there is any error during reconnection
     * @throws QueryException sqlException
     */
    @Override
    public void reconnectFailedConnection(SearchFilter searchFilter) throws QueryException {
//        if (log.isTraceEnabled()) log.trace("search connection searchFilter=" + searchFilter);
        currentConnectionAttempts.incrementAndGet();
        resetOldsBlackListHosts();

        List<HostAddress> loopAddress = new LinkedList<>(jdbcUrl.getHostAddresses());
        if (UrlHAMode.FAILOVER.equals(mode)) {
            //put the list in the following order
            // - random order not connected host
            // - random order blacklist host
            // - random order connected host
            loopAddress.removeAll(blacklist.keySet());
            Collections.shuffle(loopAddress);
            List<HostAddress> blacklistShuffle = new LinkedList<>(blacklist.keySet());
            Collections.shuffle(blacklistShuffle);
            loopAddress.addAll(blacklistShuffle);
        } else {
            //order in sequence
            loopAddress.removeAll(blacklist.keySet());
            loopAddress.addAll(blacklist.keySet());
        }

        //put connected at end
        if (currentProtocol != null && !isMasterHostFail()) {
            loopAddress.remove(currentProtocol.getHostAddress());
            //loopAddress.add(currentProtocol.getHostAddress());
        }

        MySQLProtocol.loop(this, loopAddress, blacklist, searchFilter);

        //if no error, reset failover variables
        resetMasterFailoverData();
    }


    public void switchReadOnlyConnection(Boolean mustBeReadOnly) throws QueryException {
        if (currentReadOnlyAsked.compareAndSet(!mustBeReadOnly, mustBeReadOnly)) {
            setSessionReadOnly(mustBeReadOnly);
        }
    }

    /**
     * method called when a new Master connection is found after a fallback
     * @param protocol the new active connection
     */
    @Override
    public void foundActiveMaster(Protocol protocol) throws QueryException {
        if (isExplicitClosed()) {
            proxy.lock.writeLock().lock();
            try {
                protocol.close();
            } finally {
                proxy.lock.writeLock().unlock();
            }
            return;
        }
        syncConnection(this.currentProtocol, protocol);
        proxy.lock.writeLock().lock();
        try {
            if (currentProtocol != null && !currentProtocol.isClosed()) currentProtocol.close();
            currentProtocol = protocol;
        } finally {
            proxy.lock.writeLock().unlock();
        }

        if (currentReadOnlyAsked.get()) {
            setSessionReadOnly(true);
        }

//        if (log.isDebugEnabled()) {
//            if (getMasterHostFailTimestamp() > 0) {
//                log.debug("new primary node [" + currentProtocol.getHostAddress().toString() + "] connection established after " + (System.currentTimeMillis() - getMasterHostFailTimestamp()));
//            } else log.debug("new primary node [" + currentProtocol.getHostAddress().toString() + "] connection established");
//        }

        resetMasterFailoverData();
        stopFailover();
    }


    /**
     * Throw a human readable message after a failoverException
     *
     * @param queryException internal error
     * @param reconnected    connection status
     * @throws QueryException error with failover information
     */
    @Override
    public void throwFailoverMessage(QueryException queryException, boolean reconnected) throws QueryException {
        HostAddress hostAddress = (currentProtocol != null) ? currentProtocol.getHostAddress() : null;

        String firstPart = "Communications link failure with primary" + ((hostAddress != null) ? " host " + hostAddress.host + ":" + hostAddress.port : "") + ". ";
        String error = "";
        if (jdbcUrl.getOptions().autoReconnect) {
            if (isMasterHostFail())
                error += " Driver will reconnect automatically in a few millisecond or during next query if append before";
            else error += " Driver as successfully reconnect connection";
        } else {
            if (reconnected) {
                error += " Driver as reconnect connection";
            } else {
                if (shouldReconnect()) {
                    error += " Driver will try to reconnect automatically in a few millisecond or during next query if append before";
                }
            }
        }
        if (queryException == null) {
            throw new QueryException(firstPart + error, (short) -1, SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION.getSqlState());
        } else {
            error = queryException.getMessage() + ". " + error;
            queryException.setMessage(firstPart + error);
            throw queryException;
        }
    }


    public void reconnect() throws QueryException {
        reconnectFailedConnection(new SearchFilter(true, false));
    }
}
