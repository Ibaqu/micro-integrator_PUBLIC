/*
* Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
 */
package cmd

import (
	"fmt"
	"github.com/olekukonko/tablewriter"
	"github.com/spf13/cobra"
	"github.com/wso2/micro-integrator/cmd/utils"
	"os"
)

// Show connector command related usage info
const showConnectorsCmdShortDesc = "Get information about connectors"
const showConnectorsCmdLongDesc = "Get information as a list for all the connectors\n"

var showConnectorCmdExamples = "Example:\n" +
	"To list all the connectors\n" +
	"  " + programName + " " + connectorCmdLiteral + " " + utils.ShowCommand + "\n\n"

// connectorShowCmd represents the show connector command
var connectorShowCmd = &cobra.Command{
	Use:   utils.ShowCommand,
	Short: showConnectorsCmdShortDesc,
	Long:  showConnectorsCmdLongDesc + showConnectorCmdExamples,
	Run: func(cmd *cobra.Command, args []string) {
		handleConnectorCmdArguments(args)
	},
}

func init() {
	connectorCmd.AddCommand(connectorShowCmd)
	connectorShowCmd.SetHelpTemplate(showConnectorsCmdLongDesc +
		utils.GetCmdUsageForNonArguments(programName, connectorCmdLiteral, utils.ShowCommand) +
		showConnectorCmdExamples + utils.GetCmdFlags(connectorCmdLiteral))
}

// connector argument handling method
func handleConnectorCmdArguments(args []string) {
	utils.Logln(utils.LogPrefixInfo + "Show Connector called")
	if len(args) == 0 {
		executeListConnectorCmd()
	} else if len(args) == 1 {
		if args[0] == utils.HelpCommand {
			printConnectorHelp()
		} else {
			fmt.Println("Too many arguments. See the usage below")
			printConnectorHelp()
		}
	} else {
		fmt.Println("Too many arguments. See the usage below")
		printConnectorHelp()
	}
}

func executeListConnectorCmd() {
	finalUrl := utils.GetRESTAPIBase() + utils.PrefixConnectors
	resp, err := utils.UnmarshalData(finalUrl, nil, &utils.ConnectorList{})

	if err == nil {
		// Printing the list of available Connectors
		list := resp.(*utils.ConnectorList)
		printConnectorList(*list)
	} else {
		utils.Logln(utils.LogPrefixError + "Getting List of Connectors", err)
	}
}

func printConnectorList(connectorList utils.ConnectorList) {

	if connectorList.Count > 0 {
		table := tablewriter.NewWriter(os.Stdout)
		table.SetAlignment(tablewriter.ALIGN_LEFT)

		data := []string{"NAME", "STATUS", "PACKAGE", "DESCRIPTION"}
		table.Append(data)

		for _, connector := range connectorList.Connectors {
			data = []string{connector.Name, connector.Status, connector.Package, connector.Description}
			table.Append(data)
		}
		table.SetBorder(false)
		table.SetColumnSeparator("  ")
		table.Render()
	} else {
		fmt.Println("No Connectors found")
	}
}

func printConnectorHelp()  {
	fmt.Print(showConnectorsCmdLongDesc + utils.GetCmdUsageForNonArguments(programName, connectorCmdLiteral,
		utils.ShowCommand) + showConnectorCmdExamples + utils.GetCmdFlags(connectorCmdLiteral))
}