# Superpowers Multi-Role Collaboration Project

## Overview
This project implements a multi-role collaboration framework called Superpowers, designed to facilitate efficient teamwork among different roles in software development. Each role has specific responsibilities and communicates through a defined protocol, ensuring a smooth workflow.

## Project Structure
The project is organized into several key directories and files:

- **src/**: Contains the source code for the VS Code extension.
  - **extension.ts**: Main logic for the extension, managing the status bar and workspace events.
  - **collaboration.ts**: Manages the roles and communication protocols for Superpowers collaboration.
  - **utils/**: Utility functions, including file reading operations.
    - **fileReader.ts**: Reads the CLAUDE.md file from the project root.

- **shared/**: Contains shared documents and specifications.
  - **architecture.md**: Architectural design and technical specifications.
  - **interface-spec.md**: API interfaces and data structures.
  - **task-list.md**: List of tasks and responsibilities for each role.

- **.vscode/**: Configuration files for the development environment.
  - **extensions.json**: Recommended extensions for the project.
  - **launch.json**: Debugging configuration settings.
  - **settings.json**: Workspace-specific settings.
  - **tasks.json**: Defines tasks for building and testing.

- **CLAUDE.md**: Central document for project guidelines, objectives, and collaboration protocols.

- **package.json**: Configuration file for npm, listing dependencies and scripts.

- **tsconfig.json**: TypeScript configuration file specifying compiler options.

## Setup Instructions
1. Clone the repository to your local machine.
2. Navigate to the project directory.
3. Install the necessary dependencies using npm:
   ```
   npm install
   ```
4. Open the project in your preferred code editor.

## Usage Guidelines
- Follow the defined roles and responsibilities as outlined in the shared documents.
- Use the Superpowers messaging system for communication between roles.
- Adhere to the fixed workflow: Requirement Breakdown → Architecture Design → Parallel Development → Code Review → Code Merge.

## Contribution
Contributions are welcome! Please follow the guidelines in CLAUDE.md for submitting changes and improvements to the project.