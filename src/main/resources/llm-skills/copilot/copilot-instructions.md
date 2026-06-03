# {{projectName}} Copilot Instructions

## Language: {{language}}
## Author: {{author}}

## Code Style
- Follow existing patterns in the codebase
- Minimize changes to achieve the goal
- No unnecessary comments or documentation
- Handle errors at system boundaries only

## Security
- Never hardcode credentials or API keys
- Validate all external inputs
- Follow OWASP security guidelines

## Testing
- Write tests for business logic
- Use meaningful test names (should_doX_when_Y)
- No mocking of internal code
