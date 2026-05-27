# Interface Specification for Superpowers Collaboration

## Overview
This document defines the API interfaces and data structures used in the Superpowers multi-role collaboration project. It ensures clear communication between different roles, facilitating efficient development and integration.

## API Interfaces

### Role Communication Interface
```typescript
interface RoleMessage {
    sender: string;
    recipient: string;
    message: string;
    timestamp: Date;
}
```

### Task Management Interface
```typescript
interface Task {
    id: string;
    title: string;
    description: string;
    assignedTo: string;
    status: 'pending' | 'in-progress' | 'completed';
    createdAt: Date;
    updatedAt: Date;
}
```

### Collaboration Protocols
- **Send Message**: Allows roles to send messages to each other.
- **Update Task**: Enables roles to update the status and details of tasks.
- **Fetch Tasks**: Retrieves the list of tasks assigned to a specific role.

## Data Structures

### User Role
```typescript
interface UserRole {
    id: string;
    name: string;
    responsibilities: string[];
}
```

### Project Configuration
```typescript
interface ProjectConfig {
    roles: UserRole[];
    tasks: Task[];
    apiVersion: string;
}
```

## Usage
This interface specification should be referenced by all roles to ensure consistency in communication and data handling throughout the project. Each role is responsible for adhering to these specifications when implementing their respective functionalities.