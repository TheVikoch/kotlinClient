# Kotlin Messenger Client

A modern desktop messenger client built with Kotlin and Compose Multiplatform, featuring a beautiful UI and full JWT authentication support.

## Features

- ✅ JWT Authentication (Register/Login)
- ✅ Session Management (View and revoke sessions)
- ✅ Personal and Group Chats
- ✅ Real-time Messaging
- ✅ Modern Material 3 UI
- ✅ Dark/Light Theme Support

## Prerequisites

- **JDK 17** or higher
- **Gradle** (wrapper included)

## Project Structure

```
src/jvmMain/kotlin/com/messenger/client/
├── Main.kt                          # Application entry point
├── models/
│   └── Models.kt                    # Data DTOs
├── services/
│   ├── ApiService.kt               # API client with Ktor
│   └── AuthState.kt                # Authentication state management
└── ui/
    ├── Theme.kt                    # Material 3 theme
    └── screens/
        ├── MainScreen.kt           # Main navigation
        ├── auth/
        │   ├── LoginScreen.kt      # Login UI
        │   └── RegisterScreen.kt   # Registration UI
        ├── main/
        │   └── MainMenuScreen.kt   # Main menu after login
        ├── chat/
        │   ├── ChatMenuScreen.kt   # Chat list and creation
        │   └── ChatDetailScreen.kt # Chat messages
        └── sessions/
            └── SessionsScreen.kt   # Session management
```

## Building the Project

### Using Gradle Wrapper (Recommended)

```bash
# Build the project
./gradlew build

# Run the application
./gradlew run

# On Windows
gradlew.bat build
gradlew.bat run
```

### Using Gradle Directly

```bash
gradle build
gradle run
```

## Configuration

The client connects to `http://127.0.0.1:8080` by default. To change the server URL, modify the `serverUrl` parameter in:

- [`ApiService.kt`](src/jvmMain/kotlin/com/messenger/client/services/ApiService.kt:9)

## API Endpoints Used

The client implements the following API endpoints:

### Authentication
- `POST /api/auth/register` - User registration
- `POST /api/auth/login` - User login
- `GET /api/test/protected` - Test protected endpoint
- `GET /api/test/public` - Test public endpoint
- `GET /api/auth/sessions/{userId}` - Get all sessions
- `POST /api/auth/sessions/revoke` - Revoke a session

### Chats
- `POST /api/chat/personal` - Create personal chat
- `POST /api/chat/group` - Create group chat
- `GET /api/chat` - Get all conversations
- `GET /api/chat/{id}` - Get conversation by ID
- `POST /api/chat/{id}/members` - Add member to group
- `DELETE /api/chat/{id}/members/{userId}` - Remove member from group

### Messages
- `POST /api/messages` - Send message
- `GET /api/messages/{conversationId}` - Get messages
- `GET /api/messages/{conversationId}/unread-count` - Get unread count
- `POST /api/messages/{conversationId}/read/{messageId}` - Mark as read

## Dependencies

- **Ktor Client 2.3.8** - HTTP client
- **Kotlinx Serialization 1.6.3** - JSON serialization
- **Compose Multiplatform 1.6.10** - UI framework
- **Material3 Desktop** - Modern Material Design
- **Coroutines 1.8.0** - Async operations

## UI Features

- **Material 3 Design** - Modern, clean interface
- **Responsive Layout** - Adapts to window size
- **Loading States** - Progress indicators
- **Error Handling** - User-friendly error messages
- **Form Validation** - Input validation for auth
- **Real-time Updates** - Live message display

## Running the Application

1. Ensure you have JDK 17+ installed
2. Clone or download the project
3. Navigate to the project directory
4. Run `./gradlew run` (or `gradlew.bat run` on Windows)
5. The application window will open

## Notes

- The server must be running at `http://127.0.0.1:8080` for the client to work
- All API calls are asynchronous using Kotlin coroutines
- Authentication state is managed globally via `AuthState`
- The UI automatically updates based on authentication state

## Troubleshooting

**"Could not find or load main class"**
- Ensure JDK 17+ is installed and `JAVA_HOME` is set correctly

**Connection errors**
- Verify the backend server is running at the configured URL
- Check firewall settings if connecting to a remote server

**Build errors**
- Run `./gradlew clean` and then `./gradlew build`
- Ensure you have an active internet connection for dependency resolution

## License

This project is created for educational purposes.