/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.server.executors;

import io.mycat.MycatServer;
import io.mycat.backend.BackendConnection;
import io.mycat.backend.PhysicalDBNode;
import io.mycat.backend.nio.MySQLBackendConnection;
import io.mycat.net.BufferArray;
import io.mycat.net.NetSystem;
import io.mycat.route.RouteResultset;
import io.mycat.route.RouteResultsetNode;
import io.mycat.server.ErrorCode;
import io.mycat.server.MySQLFrontConnection;
import io.mycat.server.NonBlockingSession;
import io.mycat.server.config.node.MycatConfig;
import io.mycat.server.packet.ErrorPacket;
import io.mycat.server.packet.OkPacket;
import io.mycat.server.packet.util.LoadDataUtil;
import io.mycat.util.StringUtil;

import java.util.List;

import org.apache.log4j.Logger;

/**
 * @author mycat
 */
public class SingleNodeHandler implements ResponseHandler, Terminatable,
		LoadDataResponseHandler {
	private static final Logger LOGGER = Logger
			.getLogger(SingleNodeHandler.class);
	private final RouteResultsetNode node;
	private final RouteResultset rrs;
	private final NonBlockingSession session;
	// only one thread access at one time no need lock
	private volatile byte packetId;
	private volatile boolean isRunning;
	private Runnable terminateCallBack;

	public SingleNodeHandler(RouteResultset rrs, NonBlockingSession session) {
		this.rrs = rrs;
		this.node = rrs.getNodes()[0];
		if (node == null) {
			throw new IllegalArgumentException("routeNode is null!");
		}
		if (session == null) {
			throw new IllegalArgumentException("session is null!");
		}
		this.session = session;
	}

	@Override
	public void terminate(Runnable callback) {
		boolean zeroReached = false;

		if (isRunning) {
			terminateCallBack = callback;
		} else {
			zeroReached = true;
		}

		if (zeroReached) {
			callback.run();
		}
	}

	private void endRunning() {
		Runnable callback = null;
		if (isRunning) {
			isRunning = false;
			callback = terminateCallBack;
			terminateCallBack = null;
		}

		if (callback != null) {
			callback.run();
		}
	}

	public void execute() throws Exception {
		MySQLFrontConnection sc = session.getSource();
		this.isRunning = true;
		this.packetId = 0;
		final BackendConnection conn = session.getTarget(node);
		if (session.tryExistsCon(conn, node)) {
			_execute(conn);
		} else {
			// create new connection

			MycatConfig conf = MycatServer.getInstance().getConfig();
			PhysicalDBNode dn = conf.getDataNodes().get(node.getName());
			dn.getConnection(dn.getDatabase(), sc.isAutocommit(), node, this,
					node);
		}

	}

	@Override
	public void connectionAcquired(final BackendConnection conn) {
		session.bindConnection(node, conn);
		_execute(conn);

	}

	private void _execute(BackendConnection conn) {
		if (session.closed()) {
			endRunning();
			session.clearResources(true);
			return;
		}
		conn.setResponseHandler(this);
		try {
			conn.execute(node, session.getSource(), session.getSource()
					.isAutocommit());
		} catch (Exception e1) {
			executeException(conn, e1);
			return;
		}
	}

	private void executeException(BackendConnection c, Exception e) {
		ErrorPacket err = new ErrorPacket();
		err.packetId = ++packetId;
		err.errno = ErrorCode.ERR_FOUND_EXCEPION;
		err.message = StringUtil.encode(e.toString(), session.getSource()
				.getCharset());

		this.backConnectionErr(err, c);
	}

	@Override
	public void connectionError(Throwable e, BackendConnection conn) {

		endRunning();
		ErrorPacket err = new ErrorPacket();
		err.packetId = ++packetId;
		err.errno = ErrorCode.ER_NEW_ABORTING_CONNECTION;
		err.message = StringUtil.encode(e.getMessage(), session.getSource()
				.getCharset());
		MySQLFrontConnection source = session.getSource();
		err.write(source);
	}

	@Override
	public void errorResponse(byte[] data, BackendConnection conn) {
		ErrorPacket err = new ErrorPacket();
		err.read(data);
		err.packetId = ++packetId;
		backConnectionErr(err, conn);

	}

	private void backConnectionErr(ErrorPacket errPkg, BackendConnection conn) {
		endRunning();
		String errmgs = " errno:" + errPkg.errno + " "
				+ new String(errPkg.message);
		LOGGER.warn("execute  sql err :" + errmgs + " con:" + conn);
		session.releaseConnectionIfSafe(conn, LOGGER.isDebugEnabled(), false);
		MySQLFrontConnection source = session.getSource();
		source.setTxInterrupt(errmgs);
		errPkg.write(source);
	}

	@Override
	public void okResponse(byte[] data, BackendConnection conn) {
		boolean executeResponse = conn.syncAndExcute();
		if (executeResponse) {
			session.releaseConnectionIfSafe(conn, LOGGER.isDebugEnabled(),
					false);
			endRunning();
			MySQLFrontConnection source = session.getSource();
			OkPacket ok = new OkPacket();
			ok.read(data);
			if (rrs.isLoadData()) {
				byte lastPackId = source.getLoadDataInfileHandler()
						.getLastPackId();
				ok.packetId = ++lastPackId;// OK_PACKET
				source.getLoadDataInfileHandler().clear();
			} else {
				ok.packetId = ++packetId;// OK_PACKET
			}
			ok.serverStatus = source.isAutocommit() ? 2 : 1;
			source.setLastInsertId(ok.insertId);
			ok.write(source);

		}
	}

	@Override
	public void rowEofResponse(byte[] eof, BackendConnection conn) {
		MySQLFrontConnection source = session.getSource();

		// 判断是调用存储过程的话不能在这里释放链接
		if (!rrs.isCallStatement()) {
			session.releaseConnectionIfSafe(conn, LOGGER.isDebugEnabled(),
					false);
			endRunning();
		}

		eof[3] = ++packetId;
		source.write(eof);
	}

	@Override
	public void fieldEofResponse(byte[] header, List<byte[]> fields,
			byte[] eof, BackendConnection conn) {
		header[3] = ++packetId;
		MySQLFrontConnection source = session.getSource();
		BufferArray bufferArray = NetSystem.getInstance().getBufferPool()
				.allocateArray();
		bufferArray.write(header);
		for (int i = 0, len = fields.size(); i < len; ++i) {
			byte[] field = fields.get(i);
			field[3] = ++packetId;
			bufferArray.write(field);
		}
		eof[3] = ++packetId;
		bufferArray.write(eof);
		source.write(bufferArray);
	}

	@Override
	public void rowResponse(byte[] row, BackendConnection conn) {
		row[3] = ++packetId;
		session.getSource().write(row);
	}

	@Override
	public void connectionClose(BackendConnection conn, String reason) {
		ErrorPacket err = new ErrorPacket();
		err.packetId = ++packetId;
		err.errno = ErrorCode.ER_ERROR_ON_CLOSE;
		err.message = StringUtil.encode(reason, session.getSource()
				.getCharset());
		this.backConnectionErr(err, conn);

	}

	public void clearResources() {

	}

	@Override
	public void requestDataResponse(byte[] data, BackendConnection conn) {
		LoadDataUtil.requestFileDataResponse(data,
				(MySQLBackendConnection) conn);
	}

	@Override
	public String toString() {
		return "SingleNodeHandler [node=" + node + ", packetId=" + packetId
				+ "]";
	}

}