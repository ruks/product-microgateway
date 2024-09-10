/*
 *  Copyright (c) 2024, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

// Package utills holds the implementation for common utility functions
package utills

import (
	"strings"
)

// GetIsOrganizationInList checks whether the orgID is in the cbOrganization list
func GetIsOrganizationInList(orgID string, cbOrganizationList string) bool {
	cbOrganizationsList := strings.Split(cbOrganizationList, ",")

	for _, org := range cbOrganizationsList {
		if org == orgID {
			return true
		}
	}

	return false
}
