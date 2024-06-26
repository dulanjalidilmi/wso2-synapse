/*
 * Copyright (c) 2024, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.synapse.mediators.bsf.access.control;

/**
 * Constants related to Script Mediator access control.
 */
public class AccessControlConstants {
    public static String LIMIT_CLASS_ACCESS_PREFIX = "limit_java_class_access_in_scripts.";
    public static String LIMIT_NATIVE_OBJECT_ACCESS_PREFIX = "limit_java_native_object_access_in_scripts.";
    public static String ENABLE = "enable";
    public static String LIST_TYPE = "list_type";
    public static String CLASS_PREFIXES = "class_prefixes";
    public static String OBJECT_NAMES = "object_names";
}
