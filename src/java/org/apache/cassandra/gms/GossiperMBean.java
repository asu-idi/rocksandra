/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.gms;

import java.net.UnknownHostException;

public interface GossiperMBean
{
    public long getEndpointDowntime(String address) throws UnknownHostException;

    public int getCurrentGenerationNumber(String address) throws UnknownHostException;

    public void unsafeAssassinateEndpoint(String address) throws UnknownHostException;

    public void assassinateEndpoint(String address) throws UnknownHostException;

    public double getReachableQuarantineThreshold();

    public void setReachableQuarantineThreshold(double value);

    public double getUnreachableQuarantineThreshold();

    public void setUnreachableQuarantineThreshold(double value);

}